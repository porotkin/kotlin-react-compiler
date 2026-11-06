package com.github.porotkin.reactcompiler.component

import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
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

/** `type()` or `type { ... }`, optionally keyed through `setDefaultKey`. */
internal class Element(
    val call: IrCall,
    val defaultKey: IrExpression?,
    /** The receiver of the element body, read as the element's attrs object; `null` without a body. */
    val receiver: IrValueSymbol?,
    val propsType: IrType,
    val items: List<Item>,
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
