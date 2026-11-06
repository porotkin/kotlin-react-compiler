package com.github.porotkin.reactcompiler.component

import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.unwrapCoercion
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.errorWithoutSource
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.BaseSourcelessDiagnosticRendererFactory
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irAnnotation
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.substitute
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val ELEMENT_WRAPPER = FqName("com.github.porotkin.reactcompiler.ElementWrapper")
private val EAGER_INITIALIZATION = FqName("kotlin.js.EagerInitialization")
private val ELEMENT_BUILDER = FqName("react.ElementBuilder")
private val SET_DEFAULT_KEY = FqName("react.setDefaultKey")
private val KOTLIN_WITH = FqName("kotlin.with")
private val FUNCTION_1 = FqName("kotlin.Function1")

/**
 * An `@ElementWrapper` is a final, non-overriding `operator fun invoke` on a class with a single public
 * `ElementType` property. It takes a `ChildrenBuilder`, prop parameters matching writable properties on the
 * element's `PropsWithChildren` type, and a `ChildrenBuilder.() -> Unit` block, and its body only invokes that
 * `ElementType` property, assigning each parameter to the matching prop and calling `block()`.
 *
 * Cross-module trust: in Kotlin K2, `fir2ir` builds external `IrSimpleFunction` symbols in downstream modules from
 * `.klib` FIR metadata rather than IR flags. Verified declarations are stamped in `.klib` metadata with
 * `@kotlin.js.EagerInitialization` via `metadataDeclarationRegistrar`. Because `@EagerInitialization` has
 * `@Target(PROPERTY)` in the stdlib, user source code cannot place it on a function without a compiler target error,
 * so downstream modules can reject `@ElementWrapper` functions from modules compiled without this plugin.
 */
internal object ElementWrappers {
    /** Reports an `@ElementWrapper` declaration that breaks the contract; otherwise stamps it as verified in metadata. */
    fun checkDeclaration(
        function: IrSimpleFunction,
        file: IrFile,
        context: IrPluginContext,
        symbols: ReactSymbols,
    ) {
        if (!function.hasAnnotation(ELEMENT_WRAPPER)) return
        val signature = signatureOf(function, symbols)
            ?: return report(
                context,
                function,
                file,
                "must be a final, non-overriding, non-inline, non-external operator fun invoke on a class with one public ElementType property, taking ChildrenBuilder, prop parameters, and a ChildrenBuilder.() -> Unit block",
            )
        val invalidProp = signature.propParameters.firstOrNull { signature.propsClass.writablePropertySetter(it.name) == null }
        if (invalidProp != null) {
            return report(context, function, file, "parameter '${invalidProp.name}' must match a writable property on ${signature.propsClass.name}")
        }
        val propSetters = signature.propParameters.map { signature.propsClass.writablePropertySetter(it.name)!! }
        if (!hasValidBody(function, signature, propSetters)) {
            return report(
                context,
                function,
                file,
                "must only delegate to its ElementType property, assigning each parameter to the matching prop and calling block()",
            )
        }
        if (!function.hasAnnotation(EAGER_INITIALIZATION)) {
            context.metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement(
                function,
                listOf(DeclarationIrBuilder(context, function.symbol).irAnnotation(symbols.eagerInitialization)),
            )
        }
    }

    /** The verified `ElementType` getter, `Props` type, and prop setters for an `@ElementWrapper` [function], or `null`. */
    fun resolve(
        function: IrSimpleFunction,
        symbols: ReactSymbols,
    ): VerifiedWrapper? {
        if (!function.hasAnnotation(ELEMENT_WRAPPER)) return null
        val signature = signatureOf(function, symbols) ?: return null
        val propSetters = signature.propParameters.map { signature.propsClass.writablePropertySetter(it.name) ?: return null }
        val verified = if (function.body != null) hasValidBody(function, signature, propSetters) else function.hasAnnotation(EAGER_INITIALIZATION)
        return VerifiedWrapper(signature.componentGetter, signature.propsType, propSetters).takeIf { verified }
    }

    private fun signatureOf(
        function: IrSimpleFunction,
        symbols: ReactSymbols,
    ): WrapperSignature? {
        if (
            function.name.asString() != "invoke" ||
            !function.isOperator ||
            function.modality != Modality.FINAL ||
            function.overriddenSymbols.isNotEmpty() ||
            function.isInline ||
            function.isExternal ||
            !function.returnType.isUnit() ||
            function.parameters.size < 3
        ) {
            return null
        }
        val dispatchReceiver = function.parameters[0].takeIf { it.kind == IrParameterKind.DispatchReceiver } ?: return null
        val builderParameter = function.parameters[1].takeIf {
            (it.kind == IrParameterKind.Context || it.kind == IrParameterKind.ExtensionReceiver) &&
                it.type.classOrNull == symbols.childrenBuilderClass
        } ?: return null
        val blockParameter = function.parameters.last().takeIf {
            it.kind == IrParameterKind.Regular &&
                it.varargElementType == null &&
                it.defaultValue == null &&
                it.type.isChildrenBuilderBlock(symbols)
        } ?: return null
        val propParameters = function.parameters.subList(2, function.parameters.lastIndex)
        if (propParameters.any { it.kind != IrParameterKind.Regular || it.varargElementType != null }) return null
        val wrapperClass = dispatchReceiver.type.classOrNull?.owner ?: return null
        val componentGetter = wrapperClass.declarations
            .filterIsInstance<IrProperty>()
            .filter { it.visibility == DescriptorVisibilities.PUBLIC }
            .mapNotNull { it.getter }
            .singleOrNull { it.returnType.isSubtypeOfClass(symbols.elementTypeClass) }
            ?.symbol ?: return null
        val propsType = componentGetter.owner.returnType.elementPropsType(symbols) ?: return null
        val propsClass = propsType.classOrNull?.owner ?: return null
        return WrapperSignature(dispatchReceiver, builderParameter, propParameters, blockParameter, componentGetter, propsType, propsClass)
    }

    private fun hasValidBody(
        function: IrSimpleFunction,
        signature: WrapperSignature,
        propSetters: List<IrSimpleFunctionSymbol>,
    ): Boolean {
        val outer = function.unwrappedStatements(signature.builderParameter.symbol) ?: return false
        if (outer.size != 1) return false
        val (activeBuilder, elementStatement) = unwrapWithBuilder(outer.single(), signature.builderParameter.symbol) ?: return false
        val elementCall = elementStatement.unwrapKeyedComposite(activeBuilder) as? IrCall ?: return false
        if (
            elementCall.symbol.owner.name.asString() != "invoke" ||
            elementCall.arguments.size != 3 ||
            (elementCall.arguments[0] as? IrGetValue)?.symbol != activeBuilder ||
            elementCall.symbol.owner.allOverridden(includeSelf = true).none { it.hasAnnotation(ELEMENT_BUILDER) }
        ) {
            return false
        }
        val getterCall = elementCall.arguments[1] as? IrCall ?: return false
        if (
            getterCall.symbol != signature.componentGetter ||
            (getterCall.arguments.singleOrNull() as? IrGetValue)?.symbol != signature.dispatchReceiver.symbol
        ) {
            return false
        }
        val elementFn = (elementCall.arguments[2] as? IrFunctionExpression)?.function ?: return false
        val attrsReceiver = elementFn.parameters.singleOrNull()?.symbol ?: return false
        val inner = elementFn.unwrappedStatements(attrsReceiver) ?: return false
        if (inner.size != signature.propParameters.size + 1) return false
        for (index in signature.propParameters.indices) {
            val parameter = signature.propParameters[index]
            val expectedSetter = propSetters[index].owner
            val call = inner[index] as? IrCall ?: return false
            if (
                expectedSetter !in call.symbol.owner.allOverridden(includeSelf = true) ||
                call.arguments.size != 2 ||
                (call.arguments[0]?.unwrapImplicitCast() as? IrGetValue)?.symbol != attrsReceiver ||
                (call.arguments[1]?.unwrapImplicitCast() as? IrGetValue)?.symbol != parameter.symbol
            ) {
                return false
            }
        }
        val blockCall = inner.last() as? IrCall ?: return false
        return blockCall.symbol.owner.name.asString() == "invoke" &&
            blockCall.arguments.size == 2 &&
            (blockCall.arguments[0] as? IrGetValue)?.symbol == signature.blockParameter.symbol &&
            (blockCall.arguments[1]?.unwrapImplicitCast() as? IrGetValue)?.symbol == attrsReceiver
    }

    private fun report(
        context: IrPluginContext,
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
        context.diagnosticReporter.report(ElementWrapperErrors.ELEMENT_WRAPPER, "@ElementWrapper ${function.name} $problem", location)
    }
}

internal class VerifiedWrapper(
    val componentGetter: IrSimpleFunctionSymbol,
    val propsType: IrType,
    val propSetters: List<IrSimpleFunctionSymbol>,
)

private class WrapperSignature(
    val dispatchReceiver: IrValueParameter,
    val builderParameter: IrValueParameter,
    val propParameters: List<IrValueParameter>,
    val blockParameter: IrValueParameter,
    val componentGetter: IrSimpleFunctionSymbol,
    val propsType: IrType,
    val propsClass: IrClass,
)

private fun IrType.isChildrenBuilderBlock(symbols: ReactSymbols): Boolean {
    if (this !is IrSimpleType || classFqName != FUNCTION_1 || arguments.size != 2) return false
    val receiver = (arguments[0] as? IrTypeProjection)?.type
    val result = (arguments[1] as? IrTypeProjection)?.type
    return receiver?.classOrNull == symbols.childrenBuilderClass && result?.isUnit() == true
}

private fun IrType.elementPropsType(symbols: ReactSymbols): IrType? {
    val simple = this as? IrSimpleType ?: return null
    if (simple.classOrNull == symbols.elementTypeClass) {
        return (simple.arguments.singleOrNull() as? IrTypeProjection)?.type?.takeIf { it.isSubtypeOfClass(symbols.propsWithChildrenClass) }
    }
    val cls = simple.classOrNull?.owner ?: return null
    val substitution = cls.typeParameters.map { it.symbol }.zip(simple.arguments.mapNotNull { (it as? IrTypeProjection)?.type }).toMap()
    return cls.superTypes.firstNotNullOfOrNull { it.substitute(substitution).elementPropsType(symbols) }
}

private fun IrClass.writablePropertySetter(name: Name): IrSimpleFunctionSymbol? =
    declarations.firstNotNullOfOrNull { (it as? IrProperty)?.takeIf { p -> p.name == name }?.setter?.symbol }
        ?: superTypes.firstNotNullOfOrNull { it.classOrNull?.owner?.writablePropertySetter(name) }

private fun unwrapWithBuilder(
    statement: IrStatement,
    builder: IrValueSymbol,
): Pair<IrValueSymbol, IrStatement>? {
    if (statement is IrCall && statement.symbol.owner.fqNameWhenAvailable == KOTLIN_WITH && statement.arguments.size == 2) {
        if ((statement.arguments[0] as? IrGetValue)?.symbol != builder) return null
        val withFn = (statement.arguments[1] as? IrFunctionExpression)?.function ?: return null
        val innerBuilder = withFn.parameters.singleOrNull()?.symbol ?: return null
        val inner = withFn.unwrappedStatements(innerBuilder) ?: return null
        return inner.singleOrNull()?.let { innerBuilder to it }
    }
    return builder to statement
}

private fun IrSimpleFunction.unwrappedStatements(builder: IrValueSymbol): List<IrStatement>? =
    (body as? IrBlockBody)?.statements?.trimTrailingReturn(symbol)?.flattenPlainBlocks(builder)

private fun List<IrStatement>.trimTrailingReturn(owner: IrFunctionSymbol): List<IrStatement> {
    val last = lastOrNull() as? IrReturn ?: return this
    if (last.returnTargetSymbol != owner) return this
    val value = last.value
    return if (value is IrGetObjectValue && value.type.isUnit()) dropLast(1) else dropLast(1) + value
}

private fun List<IrStatement>.flattenPlainBlocks(builder: IrValueSymbol): List<IrStatement> =
    flatMap { statement ->
        val s = statement.unwrapCoercion()
        if (s is IrContainerExpression && s.unwrapKeyedComposite(builder) === s) s.statements.flattenPlainBlocks(builder) else listOf(s)
    }

private fun IrStatement.unwrapKeyedComposite(builder: IrValueSymbol): IrStatement {
    if (this !is IrComposite || statements.size != 2) return this
    val setKey = statements[0].unwrapCoercion() as? IrCall ?: return this
    return if (setKey.symbol.owner.fqNameWhenAvailable == SET_DEFAULT_KEY && (setKey.arguments.getOrNull(0) as? IrGetValue)?.symbol == builder) {
        statements[1].unwrapCoercion()
    } else {
        this
    }
}

private tailrec fun IrExpression.unwrapImplicitCast(): IrExpression =
    if (this is IrTypeOperatorCall && operator == IrTypeOperator.IMPLICIT_CAST) argument.unwrapImplicitCast() else this

private object ElementWrapperErrors : KtDiagnosticsContainer() {
    val ELEMENT_WRAPPER by errorWithoutSource()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Messages

    object Messages : BaseSourcelessDiagnosticRendererFactory() {
        override val MAP by KtDiagnosticFactoryToRendererMap("ElementWrapper") { map ->
            map.put(ELEMENT_WRAPPER, MESSAGE_PLACEHOLDER)
        }
    }
}
