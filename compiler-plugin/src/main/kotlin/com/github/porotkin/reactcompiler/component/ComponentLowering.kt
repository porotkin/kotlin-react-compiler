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
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWith
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
    private val analyzer = ComponentAnalyzer(symbols)
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
        return lowerFactoryCall(expression)
    }

    /** `FC { }` with a lowerable lambda. */
    private fun lowerFactoryCall(call: IrCall): IrExpression {
        val argument = call.arguments[0]
        if (argument !is IrFunctionExpression || !lowerLambda(argument)) return call
        return builder(call.symbol).irImplicitCast(argument, call.type).also(components::add)
    }

    /** Rewrites a `ChildrenBuilder.(props) -> Unit` lambda into a `(props) -> ReactNode?` one, if its body can be. */
    private fun lowerLambda(lambda: IrFunctionExpression): Boolean {
        val fn = lambda.function
        val builder = fn.parameters.singleOrNull {
            it.kind == IrParameterKind.ExtensionReceiver && it.type.classOrNull == symbols.childrenBuilderClass
        } ?: return false
        val items = analyzer.analyze(fn, builder.symbol) ?: return false
        rewrite(fn, builder, items)
        lambda.type = context.irBuiltIns.functionN(fn.parameters.size).typeWith(fn.parameters.map { it.type } + symbols.reactNode)
        return true
    }

    private fun rewrite(
        fn: IrSimpleFunction,
        builderParameter: IrValueParameter,
        items: List<Item>,
    ) {
        ComponentEmitter(fn, symbols, builder(fn.symbol), fileEntry).rewrite(builderParameter, items)
    }
}
