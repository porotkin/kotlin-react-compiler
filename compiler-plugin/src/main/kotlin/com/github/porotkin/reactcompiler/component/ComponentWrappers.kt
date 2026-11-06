package com.github.porotkin.reactcompiler.component

import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.count
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.errorWithoutSource
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.BaseSourcelessDiagnosticRendererFactory
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.substitute
import org.jetbrains.kotlin.name.FqName

private val COMPONENT_WRAPPER = FqName("com.github.porotkin.reactcompiler.ComponentWrapper")

/**
 * A `@ComponentWrapper` function returns `FC` and takes a `ChildrenBuilder.(props) -> Unit` render lambda that it
 * only passes to `FC(render)` or to another wrapper. Its body is rewritten to take the already-built component,
 * so each call site passes its lambda lowered, or `FC(lambda)` when the lambda cannot be lowered.
 */
internal class ComponentWrappers(
    private val context: IrPluginContext,
    private val symbols: ReactSymbols,
) {
    /** Reports a wrapper breaking the contract; otherwise marks its render parameter as a plain value, even when inline. */
    fun checkDeclaration(
        function: IrSimpleFunction,
        file: IrFile,
    ) {
        if (!function.hasAnnotation(COMPONENT_WRAPPER)) return
        val body = function.body ?: return
        val render = renderParameter(function)
            ?: return report(function, file, "must return FC and take exactly one ChildrenBuilder lambda parameter accepted by FC")
        val reads = body.count { it is IrGetValue && it.symbol == render.symbol }
        val forwards = body.count {
            it is IrCall &&
                (it.symbol in symbols.componentFactories || renderParameter(it.symbol.owner) != null) &&
                it.arguments.any { argument -> (argument as? IrGetValue)?.symbol == render.symbol }
        }
        if (reads == 0 || reads != forwards) {
            return report(function, file, "must use its render parameter only as FC(render) or forward it to another wrapper")
        }
        render.isCrossinline = false
        render.isNoinline = true
    }

    /** Whether [expression] reads the render parameter of a wrapper, which holds an already-built component. */
    fun isRender(expression: IrExpression?): Boolean {
        val parameter = (expression as? IrGetValue)?.symbol?.owner as? IrValueParameter ?: return false
        return renderParameter(parameter.parent as? IrSimpleFunction ?: return false) == parameter
    }

    /** Passes the render lambda of a wrapper [call] lowered by [lower], or as `FC(lambda)` when it cannot be. */
    fun lowerCall(
        call: IrCall,
        lower: (IrFunctionExpression) -> Boolean,
    ) {
        val wrapper = call.symbol.owner
        val render = renderParameter(wrapper) ?: return
        val argument = call.arguments[render.indexInParameters] ?: return
        if (isRender(argument)) return
        val renderType = render.type.substitute(wrapper.typeParameters.map { it.symbol }.zip(call.typeArguments.map { it!! }).toMap())
        val b = DeclarationIrBuilder(context, call.symbol)
        val component = if (argument is IrFunctionExpression && lower(argument)) argument else b.fc(argument, renderType as IrSimpleType)
        call.arguments[render.indexInParameters] = b.irImplicitCast(component, renderType)
    }

    /** `FC(render)`, built at the call site so a lambda that cannot be lowered still becomes a component. */
    private fun DeclarationIrBuilder.fc(
        render: IrExpression,
        renderType: IrSimpleType,
    ): IrExpression {
        val factory = factoryFor(renderType)!!
        val propsType = (renderType.arguments[1] as IrTypeProjection).type
        return irCall(factory, symbols.fcClass.typeWith(propsType)).apply {
            if (factory.owner.typeParameters.isNotEmpty()) typeArguments[0] = propsType
            arguments[0] = render
        }
    }

    /** The parameter of a [function] annotated `@ComponentWrapper` that takes the render lambda, if the signature fits. */
    private fun renderParameter(function: IrSimpleFunction): IrValueParameter? {
        if (!function.hasAnnotation(COMPONENT_WRAPPER) || !function.returnType.isSubtypeOfClass(symbols.fcClass)) return null
        return function.parameters.singleOrNull { it.type.isRenderLambda() }
    }

    /** `ChildrenBuilder.(props) -> Unit`, the lambda type of an `FC` factory. */
    private fun IrType.isRenderLambda(): Boolean {
        if (this !is IrSimpleType || factoryFor(this) == null) return false
        val receiver = (arguments.firstOrNull() as? IrTypeProjection)?.type
        val result = (arguments.lastOrNull() as? IrTypeProjection)?.type
        return receiver?.classOrNull == symbols.childrenBuilderClass && result?.isUnit() == true
    }

    private fun factoryFor(renderType: IrSimpleType): IrSimpleFunctionSymbol? =
        symbols.componentFactories.firstOrNull { it.owner.parameters[0].type.classOrNull == renderType.classOrNull }

    private fun report(
        function: IrSimpleFunction,
        file: IrFile,
        problem: String,
    ) {
        val entry = file.fileEntry
        val location = CompilerMessageLocation.create(
            entry.name,
            entry.getLineNumber(function.startOffset) + 1,
            entry.getColumnNumber(function.startOffset) + 1,
            null,
        )
        context.diagnosticReporter.report(ComponentWrapperErrors.COMPONENT_WRAPPER, "@ComponentWrapper ${function.name} $problem", location)
    }
}

private object ComponentWrapperErrors : KtDiagnosticsContainer() {
    val COMPONENT_WRAPPER by errorWithoutSource()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Messages

    object Messages : BaseSourcelessDiagnosticRendererFactory() {
        override val MAP by KtDiagnosticFactoryToRendererMap("ComponentWrapper") { map ->
            map.put(COMPONENT_WRAPPER, MESSAGE_PLACEHOLDER)
        }
    }
}
