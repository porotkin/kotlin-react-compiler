package com.github.porotkin.reactcompiler.component

import com.github.porotkin.reactcompiler.ReactLowering
import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.lint.LintReporter
import com.github.porotkin.reactcompiler.lint.LintRule
import com.github.porotkin.reactcompiler.optsOutOfMemo
import com.github.porotkin.reactcompiler.reportInfo
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrFileEntry
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
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
 * React Compiler can analyze ([ComponentAnalyzer] reads the body, [ComponentEmitter] rewrites it). The render lambdas
 * passed to [ComponentWrappers] are lowered the same way, and so are the [ComponentHelpers] these bodies call.
 */
internal class ComponentLowering(
    context: IrPluginContext,
    symbols: ReactSymbols,
    private val lint: LintReporter,
) : ReactLowering(context, symbols) {
    private val wrappers = ComponentWrappers(context, symbols)
    private lateinit var fileEntry: IrFileEntry
    private lateinit var helpers: ComponentHelpers

    override fun lower(irFile: IrFile) {
        fileEntry = irFile.fileEntry
        helpers = ComponentHelpers(irFile, symbols, ::rewrite)
        super.lower(irFile)
        helpers.install()
    }

    override fun visitFunctionNew(declaration: IrFunction): IrStatement {
        if (declaration is IrSimpleFunction) {
            wrappers.checkDeclaration(declaration, currentFile)
            ElementWrappers.checkDeclaration(declaration, currentFile, context, symbols)
        }
        return super.visitFunctionNew(declaration)
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)
        if (expression.symbol in symbols.componentFactories) return lowerFactoryCall(expression)
        wrappers.lowerCall(expression, currentFile, ::lowerLambda)
        return expression
    }

    /** `FC { }` with a lowerable lambda, or `FC(render)` in a wrapper, whose render parameter now holds the component. */
    private fun lowerFactoryCall(call: IrCall): IrExpression {
        val argument = call.arguments[0]
        if (wrappers.isRender(argument)) return builder(call.symbol, call).irImplicitCast(argument!!, call.type)
        if (argument !is IrFunctionExpression || !lowerLambda(argument)) return call
        return builder(call.symbol, call).irImplicitCast(argument, call.type)
    }

    /** Rewrites a `ChildrenBuilder.(props) -> Unit` lambda into a `(props) -> ReactNode?` one, if its body can be. */
    private fun lowerLambda(lambda: IrFunctionExpression): Boolean {
        val fn = lambda.function
        val builder = fn.parameters.singleOrNull {
            it.kind == IrParameterKind.ExtensionReceiver && it.type.classOrNull == symbols.childrenBuilderClass
        } ?: return false
        val items = helpers.analyzer.analyze(fn, builder.symbol)
        if (items == null) {
            val problem = "The plugin cannot lower this code: its component stays as it is, and React Compiler does not memoize it"
            val at = helpers.analyzer.unsupported ?: lambda
            // A component that opts out of React Compiler loses nothing by staying as it is.
            if (lint.isOn(LintRule.REACT_UNSUPPORTED_SYNTAX) && !fn.optsOutOfMemo(symbols)) {
                lint.report(LintRule.REACT_UNSUPPORTED_SYNTAX, currentFile, at, "$problem. Rewrite it, or opt the component out with js(\"\\\"use no memo\\\"\").")
            } else {
                context.reportInfo(currentFile, at, problem)
            }
            return false
        }
        rewrite(fn, builder, items)
        lambda.type = context.irBuiltIns.functionN(fn.parameters.size).typeWith(fn.parameters.map { it.type } + symbols.reactNode)
        return true
    }

    private fun rewrite(
        fn: IrSimpleFunction,
        builderParameter: IrValueParameter,
        items: List<Item>,
    ) {
        ComponentEmitter(fn, symbols, builder(fn.symbol, fn), fileEntry, helpers::resolve).rewrite(builderParameter, items)
    }
}
