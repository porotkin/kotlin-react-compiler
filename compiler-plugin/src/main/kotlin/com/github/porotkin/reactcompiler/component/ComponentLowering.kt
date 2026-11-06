package com.github.porotkin.reactcompiler.component

import com.github.porotkin.reactcompiler.ReactLowering
import com.github.porotkin.reactcompiler.ReactSymbols
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrFileEntry
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irAnnotation
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Lowers `FC { props -> ... }` from `ChildrenBuilder.(props) -> Unit` into a `(props) -> ReactNode?` function
 * React Compiler can analyze ([ComponentAnalyzer] reads the body, [ComponentEmitter] rewrites it), and marks
 * properties initialized with such a component `@EagerInitialization`.
 */
internal class ComponentLowering(
    context: IrPluginContext,
    symbols: ReactSymbols,
) : ReactLowering(context, symbols) {
    private val components = mutableSetOf<IrExpression>()
    private lateinit var fileEntry: IrFileEntry

    override fun lower(irFile: IrFile) {
        fileEntry = irFile.fileEntry
        super.lower(irFile)
    }

    /** Only a bare component is safe to initialize eagerly; wrappers like `memo(FC { })` run code and stay lazy. */
    override fun visitPropertyNew(declaration: IrProperty): IrStatement {
        super.visitPropertyNew(declaration)
        if (declaration.backingField?.initializer?.expression in components) {
            declaration.annotations += builder(declaration.symbol).irAnnotation(symbols.eagerInitialization)
        }
        return declaration
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)
        if (expression.symbol !in symbols.componentFactories) return expression
        return lowerComponent(expression) ?: expression
    }

    private fun lowerComponent(call: IrCall): IrExpression? {
        val lambda = call.arguments[0] as? IrFunctionExpression ?: return null
        val fn = lambda.function
        val body = fn.body as? IrBlockBody ?: return null
        val builderParameter = fn.parameters.singleOrNull { it.kind == IrParameterKind.ExtensionReceiver } ?: return null
        val items = ComponentAnalyzer.analyze(body, builderParameter.symbol, fn.symbol) ?: return null

        fn.parameters -= builderParameter
        fn.returnType = symbols.reactNode
        lambda.type = context.irBuiltIns.functionN(fn.parameters.size)
            .typeWith(fn.parameters.map { it.type } + symbols.reactNode)

        val statements = ComponentEmitter(fn, symbols, builder(fn.symbol), fileEntry).emit(items)
        body.statements.clear()
        body.statements += statements
        body.patchDeclarationParents(fn)

        return builder(fn.symbol).irImplicitCast(lambda, call.type).also(components::add)
    }
}
