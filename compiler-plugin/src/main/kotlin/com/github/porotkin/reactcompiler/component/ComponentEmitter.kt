package com.github.porotkin.reactcompiler.component

import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.irJs
import com.github.porotkin.reactcompiler.irUseMemo
import com.github.porotkin.reactcompiler.irVal
import com.github.porotkin.reactcompiler.isUseMemo
import org.jetbrains.kotlin.ir.IrFileEntry
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.builders.irWhen
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrElseBranch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Writes an analyzed component body back as IR for [fn]: `"use memo"`, the [Plain] statements in order,
 * each element as a `jsx(type, attrs, key)` call bound to a local, and a `return` of the root node.
 */
internal class ComponentEmitter(
    private val fn: IrSimpleFunction,
    private val symbols: ReactSymbols,
    private val b: IrBuilderWithScope,
    private val fileEntry: IrFileEntry,
) {
    fun emit(items: List<Item>): List<IrStatement> {
        val out = mutableListOf<IrStatement>(b.irUseMemo(symbols))
        val returnsNull = object : IrElementTransformerVoid() {
            override fun visitReturn(expression: IrReturn): IrExpression {
                expression.transformChildrenVoid(this)
                return if (expression.returnTargetSymbol == fn.symbol) b.irReturn(b.irNull(symbols.reactNode)) else expression
            }
        }
        val children = emitItems(
            items = items
                .filterNot { it is Plain && it.statement.isUseMemo(symbols) }
                .map { if (it is Plain) Plain(it.statement.transform(returnsNull, null) as IrStatement) else it },
            out = out,
            props = null,
        )
        out += b.irReturn(root(children, out))
        return out
    }

    private fun emitItems(
        items: List<Item>,
        out: MutableList<IrStatement>,
        props: MutableList<Pair<IrCall, IrExpression>>?,
    ): List<IrExpression> {
        val children = mutableListOf<IrExpression>()
        for (item in items) {
            when (item) {
                is Plain -> out += item.statement
                is Prop -> props!! += item.setter to stable(item.setter.arguments.last()!!, item.setter.propertyName(), out)
                is Slot -> children += child(item.child, out)
            }
        }
        return children
    }

    private fun child(
        child: Child,
        out: MutableList<IrStatement>,
    ): IrExpression =
        when (child) {
            is Text -> stable(child.value, "text", out)
            is Element -> b.irGet(local("el", element(child), out))
            is Choice -> b.irGet(local("el", choice(child), out))
        }

    private fun element(element: Element): IrExpression {
        val out = mutableListOf<IrStatement>()
        val type = stable(element.call.arguments[1]!!, "type", out)
        val props = mutableListOf<Pair<IrCall, IrExpression>>()
        val children = emitItems(element.items, out, props)
        val attrs = local("attrs", b.irImplicitCast(b.irJs(symbols, "({})"), element.propsType), out)
        for ((setter, value) in props) {
            setter.arguments[0] = b.irGet(attrs)
            setter.arguments[setter.arguments.lastIndex] = value
            out += setter
        }
        setChildren(attrs, children, out)
        out += jsx(type, attrs, element.defaultKey ?: sourceKey(element.call))
        return block(out)
    }

    private fun choice(choice: Choice): IrExpression {
        val branches = choice.expression.branches.zip(choice.branches) { branch, items ->
            val out = mutableListOf<IrStatement>()
            out += emitItems(items, out, null).singleOrNull() ?: b.irNull(symbols.reactNode)
            if (branch is IrElseBranch) b.irElseBranch(block(out)) else b.irBranch(branch.condition, block(out))
        }
        val fallback = listOfNotNull(b.irElseBranch(b.irNull(symbols.reactNode)).takeIf { branches.last() !is IrElseBranch })
        return b.irWhen(symbols.reactNode, branches + fallback)
    }

    private fun root(
        children: List<IrExpression>,
        out: MutableList<IrStatement>,
    ): IrExpression =
        when (children.size) {
            0 -> b.irNull(symbols.reactNode)
            1 -> children.single()
            else -> {
                val attrs = local("attrs", b.irImplicitCast(b.irJs(symbols, "({})"), symbols.fragmentProps), out)
                setChildren(attrs, children, out)
                jsx(b.irCall(symbols.fragment), attrs, null)
            }
        }

    private fun setChildren(
        attrs: IrVariable,
        children: List<IrExpression>,
        out: MutableList<IrStatement>,
    ) {
        if (children.isEmpty()) return
        out += b.irCall(symbols.childrenSetter).apply {
            arguments[0] = b.irGet(attrs)
            arguments[1] = children.singleOrNull() ?: b.irVararg(symbols.reactNode, children)
        }
    }

    private fun jsx(
        type: IrExpression,
        attrs: IrVariable,
        key: IrExpression?,
    ): IrExpression =
        b.irCall(symbols.jsx, symbols.reactNode).apply {
            typeArguments[0] = attrs.type
            arguments[0] = type
            arguments[1] = b.irGet(attrs)
            // An unset argument means "omitted", which fails to link: kotlin-react's `jsx` has no default `defaultKey`.
            arguments[2] = key ?: b.irNull()
        }

    /** [value] itself when re-reading it is safe, otherwise a local holding it, so evaluation order is kept. */
    private fun stable(
        value: IrExpression,
        name: String,
        out: MutableList<IrStatement>,
    ): IrExpression =
        if (value.isStable()) value else b.irGet(local(name, value, out))

    private fun local(
        name: String,
        value: IrExpression,
        out: MutableList<IrStatement>,
    ): IrVariable =
        irVal(fn, name, value).also(out::add)

    private fun block(statements: List<IrStatement>): IrExpression =
        b.irBlock(resultType = symbols.reactNode) { statements.forEach { +it } }

    private fun sourceKey(call: IrCall): IrExpression? {
        if (call.startOffset < 0) return null
        return b.irString("@rdk/${fileEntry.getLineNumber(call.startOffset)}_${fileEntry.getColumnNumber(call.startOffset)}")
    }
}

private fun IrCall.propertyName(): String =
    symbol.owner.correspondingPropertySymbol!!.owner.name.asString()

private fun IrExpression.isStable(): Boolean =
    when (this) {
        is IrConst, is IrFunctionExpression, is IrGetObjectValue -> true
        is IrGetValue -> (symbol.owner as? IrVariable)?.isVar != true
        else -> false
    }
