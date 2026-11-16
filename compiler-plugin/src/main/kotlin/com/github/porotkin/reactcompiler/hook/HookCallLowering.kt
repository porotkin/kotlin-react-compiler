package com.github.porotkin.reactcompiler.hook

import com.github.porotkin.reactcompiler.ReactLowering
import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.isHookName
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isSuspendFunction
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Rewrites `useMemo(deps...) { }` / `useCallback(deps...) { }` to `useMemoRaw(callback, deps)` /
 * `useCallbackRaw(callback, deps)` before Kotlin/JS turns the reordered arguments into temporaries that hide the
 * callback and dependency array from React Compiler, and expands callable references passed to custom hooks.
 * See [HookCall] for the shape of the rewritten call. Calls spreading a vararg are left as is.
 */
internal class HookCallLowering(
    context: IrPluginContext,
    symbols: ReactSymbols,
) : ReactLowering(context, symbols) {
    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)
        symbols.rawHooks[expression.symbol]?.let { return lowerRawHook(expression, it) }
        if (expression.symbol.owner.name.isHookName()) return lowerCustomHook(expression)
        return expression
    }

    private fun lowerRawHook(
        expression: IrCall,
        raw: IrSimpleFunctionSymbol,
    ): IrExpression {
        val dependencies = expression.arguments[0] as IrVararg?
        if (dependencies.hasSpread()) return expression
        val site = HookCall(context, symbols, builder(expression), currentDeclarationParent!!)
        val rawDependencies = site.dependencies(dependencies)
        val callback = site.inlineCallback(expression.arguments[1]!!, discardsReflection = true)
        return site.wrap(
            site.b.irCall(raw, expression.type).apply {
                expression.typeArguments.forEachIndexed { index, type -> typeArguments[index] = type }
                arguments[0] = callback
                arguments[1] = rawDependencies
            },
        )
    }

    /**
     * Expands the callable references among the arguments of a custom hook call. One binding a value that is not
     * stable is expanded only while the arguments before it are stable: the value moves to a local before the call.
     * A reference passed as a `KFunction` keeps its metadata, which the hook may read. A suspend lambda passed as a
     * suspend function is [deferred][HookCall.deferSuspendLambda], except to an effect hook: [EffectHookLowering]
     * rewrites those.
     */
    private fun lowerCustomHook(expression: IrCall): IrExpression {
        val site = HookCall(context, symbols, builder(expression), currentDeclarationParent!!)
        val parameters = expression.symbol.owner.parameters
        val isEffectHook = expression.symbol in symbols.effectHooks
        var precedingStable = true
        for (index in expression.arguments.indices) {
            val argument = expression.arguments[index] ?: continue
            if (
                !isEffectHook &&
                argument is IrFunctionExpression &&
                argument.function.isSuspend &&
                parameters.getOrNull(index)?.type?.isSuspendFunction() == true
            ) {
                expression.arguments[index] = site.deferSuspendLambda(argument)
            }
            val ref = argument.asCallableRef()
            if (ref != null && (precedingStable || ref.bound.all { it.isStable() })) {
                val discardsReflection = parameters.getOrNull(index)?.type?.isFunction() == true
                expression.arguments[index] = site.expand(ref, discardsReflection)
            }
            if (!argument.isStable()) precedingStable = false
        }
        return site.wrap(expression)
    }
}
