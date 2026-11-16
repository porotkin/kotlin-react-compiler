package com.github.porotkin.reactcompiler.hook

import com.github.porotkin.reactcompiler.ReactLowering
import com.github.porotkin.reactcompiler.ReactSymbols
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Rewrites `useMemo(deps...) { }` / `useCallback(deps...) { }` to `useMemoRaw(callback, deps)` /
 * `useCallbackRaw(callback, deps)` before Kotlin/JS turns the reordered arguments into temporaries that hide the
 * callback and dependency array from React Compiler. See [HookCall] for the shape of the rewritten call. Calls
 * spreading a vararg are left as is.
 */
internal class HookCallLowering(
    context: IrPluginContext,
    symbols: ReactSymbols,
) : ReactLowering(context, symbols) {
    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)
        val raw = symbols.rawHooks[expression.symbol] ?: return expression
        return lowerRawHook(expression, raw)
    }

    private fun lowerRawHook(
        expression: IrCall,
        raw: IrSimpleFunctionSymbol,
    ): IrExpression {
        val dependencies = expression.arguments[0] as IrVararg?
        if (dependencies.hasSpread()) return expression
        val site = HookCall(context, symbols, builder(expression), currentDeclarationParent!!)
        val rawDependencies = site.dependencies(dependencies)
        val callback = site.inlineCallback(expression.arguments[1]!!)
        return site.wrap(
            site.b.irCall(raw, expression.type).apply {
                expression.typeArguments.forEachIndexed { index, type -> typeArguments[index] = type }
                arguments[0] = callback
                arguments[1] = rawDependencies
            },
        )
    }
}
