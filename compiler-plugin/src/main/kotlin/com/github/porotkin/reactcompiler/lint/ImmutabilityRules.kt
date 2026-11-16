package com.github.porotkin.reactcompiler.lint

import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.isHookName
import com.github.porotkin.reactcompiler.isOnBuilder
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.util.isGetter
import org.jetbrains.kotlin.ir.util.isSetter
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass

/**
 * [LintRule.REACT_IMMUTABILITY]: props and state are never modified, and neither are, during render, hook arguments,
 * hook results, and values already passed to a hook or to an element.
 */
internal class ImmutabilityRules(
    private val file: IrFile,
    private val symbols: ReactSymbols,
    private val lint: LintSymbols,
    private val reporter: LintReporter,
    private val builtIns: IrBuiltIns,
    private val delegated: Map<IrSimpleFunctionSymbol, IrLocalDelegatedProperty>,
) {
    /** Props and state, and the `val`s read from them: snapshots that nothing may modify. */
    private val snapshots = mutableSetOf<IrValueSymbol>()

    /** Hook arguments, hook results, values passed to a hook or an element, and the `val`s read from them. */
    private val frozen = mutableSetOf<IrValueSymbol>()

    fun recordParameters(frame: Frame) {
        val function = frame.function ?: return
        if (!frame.renders) return
        val target = if (frame.scope == Scope.HOOK) frozen else snapshots
        function.arguments(withReceiver = frame.scope == Scope.HOOK).forEach { target += it.symbol }
    }

    /** The parameters of a component or hook that its caller fills in, as opposed to the builder it adds children to. */
    private fun IrFunction.arguments(withReceiver: Boolean): List<IrValueParameter> =
        parameters.filter {
            val isArgument = it.kind == IrParameterKind.Regular || withReceiver && it.kind == IrParameterKind.ExtensionReceiver
            isArgument && !it.type.isSubtypeOfClass(symbols.childrenBuilderClass)
        }

    fun recordScopeAlias(
        subject: IrExpression,
        alias: IrValueSymbol,
    ) {
        when (subject.immutability()) {
            Immutability.SNAPSHOT -> snapshots += alias
            Immutability.FROZEN -> frozen += alias
            Immutability.NONE -> {}
        }
    }

    fun recordVariable(declaration: IrVariable) {
        if (declaration.isVar) return
        val value = declaration.initializer ?: return
        when (value.immutability()) {
            Immutability.SNAPSHOT -> snapshots += declaration.symbol
            Immutability.FROZEN -> frozen += declaration.symbol
            Immutability.NONE -> {}
        }
    }

    fun checkCall(
        call: IrCall,
        callee: IrSimpleFunction,
        isPureRender: Boolean,
    ) {
        val receiver = call.receiver() ?: return
        if (!callee.isSetter && !callee.isMutatorOf(receiver.type, builtIns)) return
        val what = if (callee.isSetter) "`${callee.correspondingPropertySymbol!!.owner.name}` is assigned" else "`${callee.name}()` is called"
        val message = when (receiver.immutability()) {
            Immutability.SNAPSHOT -> "$what on props or state, which are snapshots of one render and must not be modified. Create a new value: pass it to the state setter, or to the child as a prop."
            Immutability.FROZEN -> "$what on a value React considers immutable. Hook arguments, values returned from hooks and values already passed to a hook or to an element must not be modified during render: copy the value, or change it before it is passed.".takeIf { isPureRender }
            Immutability.NONE -> null
        }
        if (message != null) reporter.report(LintRule.REACT_IMMUTABILITY, file, call, message)
    }

    /**
     * "Values are immutable after being passed to JSX", and to a hook: a local given to either is frozen from here
     * on. To an element it is given as a prop, assigned in the element's body, or as an argument of a builder call.
     */
    fun freezePassedValues(
        call: IrCall,
        callee: IrSimpleFunction,
        isHook: Boolean,
    ) {
        val isProp = callee.isSetter && call.receiver()?.type?.isSubtypeOfClass(symbols.propsClass) == true
        if (!isHook && !isProp && !call.isOnBuilder(symbols)) return
        callee.parameters.forEachIndexed { index, parameter ->
            val argument = call.arguments[index].takeIf { parameter.kind == IrParameterKind.Regular } ?: return@forEachIndexed
            val values = if (argument is IrVararg) argument.elements.filterIsInstance<IrExpression>() else listOf(argument)
            for (value in values) {
                val local = (value.unwrapCasts() as? IrGetValue)?.symbol ?: continue
                if (local.owner is IrVariable && local !in snapshots) frozen += local
            }
        }
    }

    /**
     * Whether this expression is one of the values that must not be modified, or is read from one: through
     * properties, destructuring, casts and `!!`, which all lead into the same object.
     */
    private fun IrExpression.immutability(): Immutability =
        when {
            // A ref is a mutable box, and what it holds is not part of the props, state or hook result that passed it on.
            lint.isRef(type) -> Immutability.NONE
            this is IrGetValue -> if (symbol in snapshots) Immutability.SNAPSHOT else if (symbol in frozen) Immutability.FROZEN else Immutability.NONE
            this is IrTypeOperatorCall -> argument.immutability()
            this is IrContainerExpression -> (statements.lastOrNull() as? IrExpression)?.immutability() ?: Immutability.NONE
            this is IrWhen -> branches.maxOf { it.result.immutability() }
            this is IrCall -> {
                val callee = symbol.owner
                val local = delegated[symbol]
                val receiver = receiver()
                when {
                    // The value of `var state by useState()`.
                    local != null -> if (local.delegate?.type?.let(lint::isState) == true) Immutability.SNAPSHOT else Immutability.NONE
                    callee.name.isHookName() || callee.isUseHook() -> Immutability.FROZEN
                    symbol == builtIns.checkNotNullSymbol -> arguments[0]?.immutability() ?: Immutability.NONE
                    receiver == null -> Immutability.NONE
                    // The first of `val (state, setState) = useState()`.
                    callee.name.asString() == "component1" && lint.isState(receiver.type) -> Immutability.SNAPSHOT
                    callee.isGetter || callee.isOperator && COMPONENT_N.matches(callee.name.asString()) -> receiver.immutability()
                    else -> Immutability.NONE
                }
            }
            else -> Immutability.NONE
        }
}

/** How firmly React holds a value, weakest first. */
private enum class Immutability {
    NONE,

    /** Not to be modified while rendering: it was given to React or came from it. */
    FROZEN,

    /** Never to be modified: props and state. */
    SNAPSHOT,
}

/** The operators a destructuring declaration calls. */
private val COMPONENT_N = Regex("component\\d+")
