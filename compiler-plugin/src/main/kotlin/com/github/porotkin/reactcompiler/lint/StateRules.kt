package com.github.porotkin.reactcompiler.lint

import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol

/**
 * [LintRule.REACT_SET_STATE_IN_RENDER] and [LintRule.REACT_SET_STATE_IN_EFFECT]: state is neither set unconditionally
 * during render nor set synchronously at the start of an effect unless derived from a ref.
 */
internal class StateRules(
    private val file: IrFile,
    private val lint: LintSymbols,
    private val refs: RefRules,
    private val reporter: LintReporter,
    private val delegated: Map<IrSimpleFunctionSymbol, IrLocalDelegatedProperty>,
) {
    fun checkRenderCall(
        call: IrCall,
        site: Site,
    ) {
        // Setting state on some renders only is how state is derived from props.
        if (site.conditional || site.repeated || site.afterReturn || site.inPlace || !call.setsState()) return
        reporter.report(
            LintRule.REACT_SET_STATE_IN_RENDER,
            file,
            call,
            "State is set on every render, which renders again without end. Set state in an event handler, or compute the value during render.",
        )
    }

    /**
     * An effect that sets state where it starts makes React render twice for what one render could compute. State
     * set later is not reported: in a callback the effect registers, after it suspends, or from the value of a ref,
     * which an effect is the place to read.
     */
    fun checkEffectCall(
        call: IrCall,
        enclosingFunction: Frame,
    ) {
        if (enclosingFunction.scope != Scope.EFFECT || enclosingFunction.suspended || !call.setsState() || refs.readsRef(call)) return
        reporter.report(
            LintRule.REACT_SET_STATE_IN_EFFECT,
            file,
            call,
            "State is set as soon as the effect runs, which renders the component a second time. Compute the value during render, or set state where the change happens: in an event handler.",
        )
    }

    /** `setState(value)`, or an assignment to a `by useState()` local. */
    private fun IrCall.setsState(): Boolean {
        val local = delegated[symbol]
        return if (local != null) {
            symbol == local.setter?.symbol && local.delegate?.type?.let(lint::isState) == true
        } else {
            symbol.owner.name.asString() == "invoke" && receiver()?.type?.let(lint::isStateSetter) == true
        }
    }
}
