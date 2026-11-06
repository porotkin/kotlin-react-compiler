package com.github.porotkin.reactcompiler.component

import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrType

/**
 * A statement of a `ChildrenBuilder` lambda, classified by what it contributes to the element being built.
 * Produced by [ComponentAnalyzer], consumed by [ComponentEmitter].
 */
internal sealed interface Item {
    /** Reads of the builder receiver as the element's attrs object (`title = x`), which need that object to exist. */
    val attrsUses: Int get() = 0

    /** All reads of the builder receiver this item accounts for: [attrsUses] and the calls that add children. */
    val receiverUses: Int get() = attrsUses
}

/** A statement that adds no children: hooks, locals, early returns, or statements on the element's attrs. */
internal class Plain(
    val statement: IrStatement,
    override val attrsUses: Int,
) : Item

/** The variable of `val v = x ?: run { ...; return }` past its early return, initialized with the value of the `else`. */
internal class Rebind(
    val variable: IrVariable,
    val initializer: IrExpression,
    override val attrsUses: Int,
) : Item

/** An explicit `key = ...` in an element body, passed as the 3rd argument to `jsx`. */
internal class KeyProp(
    val value: IrExpression,
) : Item {
    override val receiverUses get() = 1
}

/** An early return at the root of a component, before any child (`if (cond) { Skeleton(); return@FC }`). */
internal class Guard(
    val condition: IrExpression,
    val items: List<Item>,
) : Item {
    override val receiverUses get() = items.sumOf { it.receiverUses }
}

/** An item that adds child nodes. */
internal sealed interface Child : Item {
    /** Whether a node is added at all; a structure that adds none stays a [Plain] statement. */
    val addsChildren: Boolean get() = true
}

/** `+value` */
internal class Text(
    val value: IrExpression,
) : Child {
    override val receiverUses get() = 1
}

/** A prop passed as an argument to an element `invoke`: set via [setter] when known, or dynamically by [name]. */
internal class PropArg(
    val name: String,
    val setter: IrSimpleFunctionSymbol?,
    val value: IrExpression,
)

/** `type()`, `type { ... }`, `provider(value) { ... }` or `wrapper(props...) { ... }`, optionally keyed through `setDefaultKey`. */
internal class Element(
    val call: IrCall,
    /** For a wrapper class whose `@ElementWrapper` `invoke` takes `ChildrenBuilder`, the getter of its `ElementType` property. */
    val componentGetter: IrSimpleFunctionSymbol?,
    val defaultKey: IrExpression?,
    /** Props passed as arguments: the `value` of `provider(value) { ... }` or the verified props of an `@ElementWrapper`. */
    val props: List<PropArg>,
    /** The receiver of the element body, read as the element's attrs object; `null` without a body. */
    val receiver: IrValueSymbol?,
    val propsType: IrType,
    val items: List<Item>,
) : Child {
    override val receiverUses get() = if (defaultKey != null) 2 else 1
}

/** A call to a lowered `private fun ChildrenBuilder.Foo(props)` helper in the same file. */
internal class HelperCall(
    val call: IrCall,
) : Child {
    override val receiverUses get() = 1
}

/** `child(type, props)`, optionally keyed through `setDefaultKey`. */
internal class ChildWithProps(
    val type: IrExpression,
    val props: IrExpression,
    val defaultKey: IrExpression?,
    val propsType: IrType,
) : Child {
    override val receiverUses get() = if (defaultKey != null) 2 else 1
}

/** A child built from nested items, which also reads the receiver [conditionUses] times as attrs in its conditions. */
internal sealed class Nested(
    private val nested: List<Item>,
    private val conditionUses: Int,
) : Child {
    override val attrsUses get() = conditionUses + nested.sumOf { it.attrsUses }
    override val receiverUses get() = conditionUses + nested.sumOf { it.receiverUses }
    override val addsChildren get() = nested.any { it is Child && it.addsChildren }
}

/** An `if` / `when`; without an `else` branch it adds nothing when no condition holds. */
internal class Choice(
    val branches: List<Branch>,
    conditionUses: Int,
) : Nested(branches.flatMap { it.items }, conditionUses)

/** A [Choice] branch; the `else` branch has no [condition]. */
internal class Branch(
    val condition: IrExpression?,
    val items: List<Item>,
)

/** A `for` / `while` loop. */
internal class WhileLoop(
    val loop: IrWhileLoop,
    val items: List<Item>,
    conditionUses: Int,
) : Nested(items, conditionUses)

/** `repeat(times) { ... }` */
internal class RepeatLoop(
    val call: IrCall,
    val action: IrSimpleFunction,
    val items: List<Item>,
) : Nested(items, 0)

/** `value.let { parameter -> ... }` */
internal class LetBlock(
    val value: IrExpression,
    val parameter: IrValueParameter,
    val items: List<Item>,
) : Nested(items, 0)
