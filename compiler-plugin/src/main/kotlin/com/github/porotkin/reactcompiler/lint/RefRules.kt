package com.github.porotkin.reactcompiler.lint

import com.github.porotkin.reactcompiler.any
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.util.isSetter

/**
 * [LintRule.REACT_REFS]: a ref is neither read, written, nor passed by property reference while rendering, except for
 * lazy initialization inside `if (ref.current == null) { ... }`. Also tracks locals computed from refs so
 * [StateRules] knows when an effect derives state from a ref read.
 */
internal class RefRules(
    private val file: IrFile,
    private val lint: LintSymbols,
    private val reporter: LintReporter,
    private val builtIns: IrBuiltIns,
    private val delegated: Map<IrSimpleFunctionSymbol, IrLocalDelegatedProperty>,
) {
    /** The locals computed from the value of a ref. */
    private val readFromRefs = mutableSetOf<IrValueSymbol>()

    /** Refs inside `if (ref.current == null) { ... }`, the one place render may initialize a ref. */
    private val initializedRefs = mutableListOf<IrValueSymbol>()
    private val initializationChecks = mutableSetOf<IrCall>()

    fun recordVariable(declaration: IrVariable) {
        if (declaration.isVar) return
        val value = declaration.initializer ?: return
        if (readsRef(value)) readFromRefs += declaration.symbol
    }

    /** The ref of `ref.current == null`, whose read is part of initializing the ref. */
    fun checkInitializationCondition(condition: IrExpression): IrValueSymbol? {
        val comparison = condition as? IrCall ?: return null
        if (comparison.symbol != builtIns.eqeqSymbol && comparison.symbol != builtIns.eqeqeqSymbol) return null
        val read = comparison.arguments[0] as? IrCall ?: return null
        if ((comparison.arguments[1] as? IrConst)?.value != null || !lint.isRefAccess(read)) return null
        val ref = (read.receiver() as? IrGetValue)?.symbol ?: return null
        initializationChecks += read
        return ref
    }

    inline fun inInitialization(
        ref: IrValueSymbol?,
        block: () -> Unit,
    ) {
        if (ref != null) initializedRefs += ref
        block()
        if (ref != null) initializedRefs.removeLast()
    }

    /** Whether the value of [element] comes from a ref. */
    fun readsRef(element: IrElement): Boolean =
        element.any {
            it is IrGetValue && it.symbol in readFromRefs ||
                it is IrCall && (lint.isRefAccess(it) || delegated[it.symbol]?.delegate?.type?.let(lint::isRefValue) == true)
        }

    fun checkCall(
        call: IrCall,
        callee: IrSimpleFunction,
    ) {
        val local = delegated[call.symbol]
        val name = when {
            local != null -> local.name.takeIf { lint.isRefValue(local.delegate?.type ?: return) } ?: return
            !lint.isRefAccess(call) || call in initializationChecks -> return
            (call.receiver() as? IrGetValue)?.symbol in initializedRefs -> return
            else -> "current"
        }
        val isWrite = if (local != null) call.symbol == local.setter?.symbol else callee.isSetter
        val access = if (isWrite) "written" else "read"
        val advice = "Access refs in effects and event handlers, or keep the value in state"
        reporter.report(LintRule.REACT_REFS, file, call, "Ref value `$name` is $access during render. $advice.")
    }

    /** `ref::current` hands the ref itself to whatever takes the reference. */
    fun checkPropertyReference(
        reference: IrExpression,
        bound: IrExpression?,
        site: Site,
    ) {
        if (bound == null || !lint.isRef(bound.type)) return
        if (!site.memo && site.rendersIn { it.renders && !it.optsOut }) {
            reporter.report(
                LintRule.REACT_REFS,
                file,
                reference,
                "A property of a ref is referenced during render, which passes the ref on. Use a lambda that reads it: `{ ref.current }`.",
            )
        }
    }
}
