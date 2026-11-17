package com.github.porotkin.reactcompiler.hook

import com.github.porotkin.reactcompiler.ReactLowering
import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.StateDelegate
import com.github.porotkin.reactcompiler.find
import com.github.porotkin.reactcompiler.irVal
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLocalDelegatedPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrRichPropertyReference
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.transformFlat
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Replaces `by useRefValue()`, `by useLatest()` and `by useState()` local delegated properties before Kotlin/JS's
 * `LocalDelegatedPropertiesLowering` turns their accessors into top-level functions calling `getLocalDelegateReference`:
 *
 * - Ref delegates become plain locals whose reads and writes call their inline `getValue` / `setValue` directly,
 *   which Kotlin/JS inlines to the `.current` accesses React Compiler recognizes as ref accesses.
 * - State delegates become `component1()` / `component2()` locals (`val count = state[0]`;
 *   `val setCount = state[1]`), matching `val (count, setCount) = useState(...)`.
 */
internal class DelegateLowering(
    context: IrPluginContext,
    symbols: ReactSymbols,
) : ReactLowering(context, symbols) {
    /** Accessors of the delegated locals lowered so far, mapped to what replaces a call to them. */
    private val accessors = mutableMapOf<IrSimpleFunctionSymbol, (IrCall) -> IrExpression>()

    override fun visitBlockBody(body: IrBlockBody): IrBody {
        lowerDelegates(body.statements)
        return body
    }

    override fun visitContainerExpression(expression: IrContainerExpression): IrExpression {
        lowerDelegates(expression.statements)
        return expression
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)
        return accessors[expression.symbol]?.invoke(expression) ?: expression
    }

    /** Transforms [statements] in order, so each delegated local is replaced before the accessor calls after it. */
    private fun lowerDelegates(statements: MutableList<IrStatement>) {
        statements.transformFlat { statement ->
            (statement as? IrLocalDelegatedProperty)?.let(::lowerDelegate) ?: listOf(statement.transform(this, null) as IrStatement)
        }
    }

    private fun lowerDelegate(declaration: IrLocalDelegatedProperty): List<IrStatement>? {
        val delegate = declaration.delegate ?: return null
        val type = delegate.type
        val state = symbols.stateDelegate?.takeIf { type.isSubtypeOfClass(it.instance) }
        if (state == null && symbols.refDelegates.none { type.isSubtypeOfClass(it) }) return null
        delegate.transformChildrenVoid(this)
        return if (state != null) lowerState(declaration, delegate, state) else lowerRef(declaration, delegate)
    }

    private fun lowerRef(
        declaration: IrLocalDelegatedProperty,
        delegate: IrVariable,
    ): List<IrStatement> {
        delegate.name = declaration.name
        for (accessor in listOfNotNull(declaration.getter, declaration.setter)) {
            accessors[accessor.symbol] = operatorCall(accessor)
        }
        return listOf(delegate)
    }

    /**
     * A call to [accessor] as the `getValue` / `setValue` call in its body, with `null` for the `KProperty`, the argument
     * that makes Kotlin/JS create reflection metadata. kotlin-react's ref delegates never read it: their inline bodies
     * read and write `current`, boxing a value class through their generic `T`.
     */
    private fun operatorCall(accessor: IrSimpleFunction): (IrCall) -> IrExpression {
        val operator = checkNotNull(accessor.body?.find { it is IrCall && it.arguments.any { a -> a.isDelegatedPropertyReference() } }) {
            "No delegate operator call in ${accessor.name}"
        } as IrCall
        val value = accessor.parameters.lastOrNull()?.symbol
        return { call ->
            val b = builder(call)
            b.irCall(operator.symbol, operator.type).apply {
                operator.typeArguments.forEachIndexed { index, type -> typeArguments[index] = type }
                operator.arguments.forEachIndexed { index, argument ->
                    arguments[index] = when {
                        argument.isDelegatedPropertyReference() -> b.irNull()
                        argument is IrGetValue && argument.symbol == value -> call.arguments.single()
                        else -> argument?.deepCopyWithSymbols()
                    }
                }
            }
        }
    }

    private fun lowerState(
        declaration: IrLocalDelegatedProperty,
        delegate: IrVariable,
        state: StateDelegate,
    ): List<IrStatement> {
        val b = builder(declaration)
        val setter = declaration.setter
        // A `val` reads the state once, so its value is all it keeps.
        val instance = if (setter == null) delegate.initializer!! else b.irGet(delegate)
        val value = irVal(
            declaration.parent,
            declaration.name.asString(),
            b.irCall(state.component1, declaration.type).apply { arguments[0] = instance },
        )
        accessors[declaration.getter.symbol] = { call -> builder(call).irGet(value) }
        if (setter == null) return listOf(value)
        val setterType = state.setter.typeWith(declaration.type)
        val setValue = irVal(
            declaration.parent,
            "set" + declaration.name.asString().replaceFirstChar(Char::uppercaseChar),
            b.irCall(state.component2, setterType).apply { arguments[0] = b.irGet(delegate) },
        )
        accessors[setter.symbol] = { call ->
            builder(call).run {
                irCall(state.setValue, context.irBuiltIns.unitType).apply {
                    arguments[0] = irGet(setValue)
                    arguments[1] = call.arguments.single()!!
                }
            }
        }
        return listOf(delegate, value, setValue)
    }
}

private fun IrExpression?.isDelegatedPropertyReference(): Boolean =
    this is IrLocalDelegatedPropertyReference || this is IrRichPropertyReference
