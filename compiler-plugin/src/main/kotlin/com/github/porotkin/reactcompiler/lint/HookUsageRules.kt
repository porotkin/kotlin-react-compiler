package com.github.porotkin.reactcompiler.lint

import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.isHookName
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.ir.util.isFunctionTypeOrSubtype
import org.jetbrains.kotlin.ir.util.isGetter

/**
 * Checks hook call placement ([LintRule.REACT_RULES_OF_HOOKS]), `useMemo` return usage ([LintRule.REACT_USE_MEMO]),
 * and `use*`-named non-hook values read inside components and hooks ([LintRule.REACT_HOOKS_AS_VALUES]).
 */
internal class HookUsageRules(
    private val file: IrFile,
    private val symbols: ReactSymbols,
    private val scope: RenderScope,
    private val reporter: LintReporter,
) {
    private val reportedNames = mutableSetOf<IrValueSymbol>()

    fun checkCall(
        call: IrCall,
        callee: IrSimpleFunction,
    ) {
        if (callee.name.isHookName() || callee.isUseHook()) checkHookCall(call, callee)
        checkHookNamedProperty(call, callee)
        if (call.symbol in symbols.useMemoHooks && call.type.isUnit()) {
            report(
                LintRule.REACT_USE_MEMO,
                call,
                "The callback of `useMemo` returns nothing. `useMemo` is for computing a value: run side effects in `useEffect` or in an event handler.",
            )
        }
    }

    private fun checkHookCall(
        call: IrCall,
        callee: IrSimpleFunction,
    ) {
        val site = scope.site()
        val barrier = site.barrier
        // `use` may be called conditionally, and React's own linter lets it into callbacks.
        val isUse = callee.isUseHook()
        val problem = when {
            barrier?.scope?.isCallback == true || site.root != null && site.memo ->
                if (isUse || scope.reactFunction() == null) return else "is called in a nested function. Call hooks at the top level of a component or hook, not in callbacks"
            site.root == null -> {
                val function = (barrier?.function as? IrDeclarationWithName)?.name?.takeUnless { it.isSpecial }
                val where = if (function == null) "outside a component or hook" else "in `$function`, which is neither a component nor a hook"
                "is called $where. Call hooks only from components and from functions named `use…`"
            }
            (site.root?.function as? IrSimpleFunction)?.isSuspend == true -> "is called in a suspend function. A hook runs while React renders, which cannot wait"
            site.guarded -> "is called inside try/catch/finally. $EVERY_RENDER"
            isUse -> return
            site.repeated -> "is called in a loop. $EVERY_RENDER"
            site.conditional -> "is called conditionally. $EVERY_RENDER"
            site.afterReturn -> "is called after an early return. $EVERY_RENDER"
            site.inPlace -> "is called in a lambda that may run more than once or not at all. $EVERY_RENDER"
            else -> return
        }
        report(LintRule.REACT_RULES_OF_HOOKS, call, "Hook `${callee.name}` $problem.")
    }

    fun checkGetValue(expression: IrGetValue) {
        val value = expression.symbol.owner
        val isMisnamed = value.name.isHookName() && !value.type.isFunctionTypeOrSubtype() && scope.reactFunction() != null
        if (isMisnamed && reportedNames.add(expression.symbol)) {
            report(LintRule.REACT_HOOKS_AS_VALUES, value, notAHookMessage("${value.name}"))
        }
    }

    /** A property of an external declaration keeps its name in JavaScript, where a `use` name makes it a hook. */
    private fun checkHookNamedProperty(
        call: IrCall,
        callee: IrSimpleFunction,
    ) {
        val property = callee.correspondingPropertySymbol?.owner ?: return
        if (!property.name.isHookName() || !property.isEffectivelyExternal() || scope.reactFunction() == null) return
        val type = if (callee.isGetter) callee.returnType else callee.parameters.last().type
        if (!type.isFunctionTypeOrSubtype()) report(LintRule.REACT_HOOKS_AS_VALUES, call, notAHookMessage("${property.name}"))
    }

    fun checkTypeOperator(expression: IrTypeOperatorCall) {
        val discarded = expression.argument.takeIf { expression.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT } as? IrCall
        if (discarded != null && discarded.symbol in symbols.useMemoHooks && !discarded.type.isUnit()) {
            report(
                LintRule.REACT_USE_MEMO,
                discarded,
                "The value `useMemo` computes is not used. `useMemo` is for computing a value: run side effects in `useEffect` or in an event handler.",
            )
        }
    }

    private fun report(
        rule: LintRule,
        at: IrElement,
        message: String,
    ) {
        reporter.report(rule, file, at, message)
    }
}

private const val EVERY_RENDER = "A component or hook must call the same hooks in the same order on every render"

private fun notAHookMessage(name: String): String =
    "`$name` is not a hook, but React Compiler takes every name that starts with `use` for one. Rename it."
