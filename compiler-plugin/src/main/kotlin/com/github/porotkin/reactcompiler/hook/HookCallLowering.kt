package com.github.porotkin.reactcompiler.hook

import com.github.porotkin.reactcompiler.ReactLowering
import com.github.porotkin.reactcompiler.ReactSymbols
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Rewrites `useMemo(deps...) { }` / `useCallback(deps...) { }` to `useMemoRaw(callback, deps)` /
 * `useCallbackRaw(callback, deps)` before Kotlin/JS turns the reordered arguments into temporaries
 * that hide the callback and dependency array from React Compiler. Calls spreading a vararg are left as is.
 */
internal class HookCallLowering(
    context: IrPluginContext,
    symbols: ReactSymbols,
) : ReactLowering(context, symbols) {
    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)
        val raw = symbols.rawHooks[expression.symbol] ?: return expression
        val dependencies = expression.arguments[0] as IrVararg?
        if (dependencies?.elements?.any { it is IrSpreadElement } == true) return expression
        val b = builder(expression.symbol)
        return b.irCall(raw, expression.type).apply {
            expression.typeArguments.forEachIndexed { index, type -> typeArguments[index] = type }
            arguments[0] = expression.arguments[1]
            arguments[1] = dependencies ?: b.irVararg(context.irBuiltIns.anyNType, emptyList())
        }
    }
}
