package com.github.porotkin.reactcompiler.component

import com.github.porotkin.reactcompiler.any
import com.github.porotkin.reactcompiler.count
import com.github.porotkin.reactcompiler.unwrapCoercion
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.isChar
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.name.FqName

private val ELEMENT_BUILDER = FqName("react.ElementBuilder")
private val CHILDREN_BUILDER = FqName("react.ChildrenBuilder")
private val SET_DEFAULT_KEY = FqName("react.setDefaultKey")

/**
 * Reads a component body written against kotlin-react's `ChildrenBuilder` DSL into the [Item] model.
 * Returns `null` when the body uses the builder in a shape [ComponentEmitter] cannot reproduce,
 * which leaves the component untouched.
 */
internal object ComponentAnalyzer {
    fun analyze(
        body: IrBlockBody,
        builder: IrValueSymbol,
        owner: IrFunctionSymbol,
    ): List<Item>? =
        BuilderScope(builder, owner, isRoot = true).analyze(body.statements)
}

/** The statements written against one builder [receiver]: the component body ([isRoot]) or an element's children lambda. */
private class BuilderScope(
    private val receiver: IrValueSymbol,
    private val owner: IrFunctionSymbol,
    private val isRoot: Boolean,
) {
    fun analyze(statements: List<IrStatement>): List<Item>? {
        val trimmed = statements.trimTrailingReturn()
        val items = mutableListOf<Item>()
        for (statement in trimmed) {
            val item = classify(statement) ?: return null
            if (item is Prop && isRoot) return null
            if (item is Plain && statement.returnsTo(owner) && (!isRoot || items.any { it is Slot })) return null
            items += item
        }
        // A receiver use we did not classify (e.g. the builder escaping into a helper) makes the body unsafe to rewrite.
        return items.takeIf { trimmed.sumOf { it.uses(receiver) } == items.sumOf { it.receiverUses() } }
    }

    private fun classify(statement: IrStatement): Item? {
        val s = statement.unwrapCoercion()
        if (s is IrComposite && s.statements.size == 2) {
            val setKey = s.statements[0].unwrapCoercion() as? IrCall
            val invoke = s.statements[1].unwrapCoercion() as? IrCall
            if (
                setKey != null && invoke != null &&
                setKey.symbol.owner.fqNameWhenAvailable == SET_DEFAULT_KEY &&
                setKey.isOnReceiver() && invoke.isOnReceiver() && invoke.isElementInvoke()
            ) {
                return element(invoke, setKey.arguments[1])?.let(::Slot)
            }
        }
        if (s is IrCall && s.isOnReceiver()) {
            return when {
                s.isElementInvoke() -> element(s, null)?.let(::Slot)
                s.isUnaryPlus() -> Slot(Text(s.arguments[1]!!)).takeUnless { s.symbol.owner.parameters[1].type.isChar() }
                s.isPropSetter() -> Prop(s)
                else -> null
            }
        }
        if (s is IrWhen && s.isChild()) return choice(s)?.let(::Slot)
        return Plain(statement)
    }

    private fun choice(expression: IrWhen): Choice? {
        if (expression.returnsTo(owner)) return null
        val branches = expression.branches.map { branch ->
            val items = branch.result.branchStatements().map { classify(it) ?: return null }
            if (items.any { it is Prop } || items.count { it is Slot } > 1) return null
            items
        }
        return Choice(expression, branches)
    }

    private fun IrStatement.isChild(): Boolean =
        when (val s = unwrapCoercion()) {
            is IrComposite -> s.statements.any { it.isChild() }
            is IrCall -> s.isOnReceiver() && (s.isElementInvoke() || s.isUnaryPlus())
            is IrWhen -> s.branches.any { branch -> branch.result.branchStatements().any { it.isChild() } }
            else -> false
        }

    private fun IrCall.isOnReceiver(): Boolean =
        (arguments.getOrNull(0) as? IrGetValue)?.symbol == receiver

    private fun List<IrStatement>.trimTrailingReturn(): List<IrStatement> {
        val last = lastOrNull() as? IrReturn ?: return this
        if (last.returnTargetSymbol != owner) return this
        val value = last.value
        return if (value is IrGetObjectValue && value.type.isUnit()) dropLast(1) else dropLast(1) + value
    }
}

private fun element(
    call: IrCall,
    defaultKey: IrExpression?,
): Element? {
    if (call.arguments.size == 2) return Element(call, defaultKey, call.typeArguments[0]!!, emptyList())
    val block = (call.arguments.getOrNull(2) as? IrFunctionExpression)?.function ?: return null
    val receiver = block.parameters.singleOrNull() ?: return null
    val statements = (block.body as? IrBlockBody)?.statements ?: return null
    val items = BuilderScope(receiver.symbol, block.symbol, isRoot = false).analyze(statements) ?: return null
    return Element(call, defaultKey, receiver.type, items)
}

/** How many builder receiver reads this item accounts for. */
private fun Item.receiverUses(): Int =
    when (this) {
        is Plain -> 0
        is Prop -> 1
        is Slot -> when (val child = child) {
            is Text -> 1
            is Element -> if (child.defaultKey != null) 2 else 1
            is Choice -> child.branches.sumOf { branch -> branch.sumOf { it.receiverUses() } }
        }
    }

private fun IrCall.isElementInvoke(): Boolean =
    symbol.owner.name.asString() == "invoke" &&
        arguments.size in 2..3 &&
        symbol.owner.allOverridden(includeSelf = true).any { it.hasAnnotation(ELEMENT_BUILDER) }

private fun IrCall.isUnaryPlus(): Boolean =
    symbol.owner.name.asString() == "unaryPlus" &&
        arguments.size == 2 &&
        symbol.owner.allOverridden(includeSelf = true).any { it.parentClassOrNull?.fqNameWhenAvailable == CHILDREN_BUILDER }

private fun IrCall.isPropSetter(): Boolean {
    val property = symbol.owner.correspondingPropertySymbol?.owner ?: return false
    return property.setter == symbol.owner && property.name.asString() != "key"
}

private fun IrExpression.branchStatements(): List<IrStatement> =
    (this as? IrBlock)?.statements ?: listOf(this)

private fun IrStatement.returnsTo(owner: IrFunctionSymbol): Boolean =
    any { it is IrReturn && it.returnTargetSymbol == owner }

private fun IrStatement.uses(receiver: IrValueSymbol): Int =
    count { it is IrGetValue && it.symbol == receiver }
