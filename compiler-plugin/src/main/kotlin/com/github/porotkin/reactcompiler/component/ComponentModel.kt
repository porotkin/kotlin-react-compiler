package com.github.porotkin.reactcompiler.component

import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.types.IrType

/**
 * A statement of a `ChildrenBuilder` lambda, classified by what it contributes to the element being built.
 * Produced by [ComponentAnalyzer], consumed by [ComponentEmitter].
 */
internal sealed interface Item

/** A statement that does not touch the builder (hooks, locals, early returns); emitted unchanged. */
internal class Plain(
    val statement: IrStatement,
) : Item

/** A props setter on the element receiver, e.g. `title = "x"`. */
internal class Prop(
    val setter: IrCall,
) : Item

/** A child added to the builder. */
internal class Slot(
    val child: Child,
) : Item

internal sealed interface Child

/** `+value` */
internal class Text(
    val value: IrExpression,
) : Child

/** `type { ... }`, optionally keyed through `setDefaultKey`. */
internal class Element(
    val call: IrCall,
    val defaultKey: IrExpression?,
    val propsType: IrType,
    val items: List<Item>,
) : Child

/** An `if` / `when` whose branches each add at most one child. */
internal class Choice(
    val expression: IrWhen,
    val branches: List<List<Item>>,
) : Child
