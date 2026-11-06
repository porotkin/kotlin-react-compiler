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
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isChar
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
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
    /** Element body receivers, mapped to the locals that replace them. */
    private val substitutions = mutableMapOf<IrValueSymbol, IrVariable>()

    /** Replaces the body of [fn] with [items], dropping [builderParameter] and returning `ReactNode?`. */
    fun rewrite(
        builderParameter: IrValueParameter,
        items: List<Item>,
    ) {
        fn.parameters -= builderParameter
        fn.returnType = symbols.reactNode
        val out = mutableListOf<IrStatement>(b.irUseMemo(symbols))
        out += b.irReturn(node(items.filterNot { it is Plain && it.statement.isUseMemo(symbols) }, out))
        val substitute = object : IrElementTransformerVoid() {
            override fun visitGetValue(expression: IrGetValue): IrExpression =
                substitutions[expression.symbol]?.let(b::irGet) ?: expression
        }
        val body = fn.body as IrBlockBody
        body.statements.clear()
        out.mapTo(body.statements) { it.transform(substitute, null) as IrStatement }
        body.patchDeclarationParents(fn)
    }

    /** Emits [items] into [out] and returns the node they build: `null`, their only child, or a Fragment of them. */
    private fun node(
        items: List<Item>,
        out: MutableList<IrStatement>,
    ): IrExpression {
        val children = Children()
        items.forEach { emit(it, out, children) }
        return nodeOf(children.nodes, out)
    }

    private fun emit(
        item: Item,
        out: MutableList<IrStatement>,
        children: Children,
    ) {
        when (item) {
            is Plain -> out += item.statement.transform(returnsNull, null) as IrStatement
            is KeyProp -> children.key = stable(item.value, "key", out)
            is Child -> children.add(child(item, out))
        }
    }

    /** A return from the component now returns no node. */
    private val returnsNull = object : IrElementTransformerVoid() {
        override fun visitReturn(expression: IrReturn): IrExpression {
            expression.transformChildrenVoid(this)
            return if (expression.returnTargetSymbol == fn.symbol) b.irReturn(b.irNull(symbols.reactNode)) else expression
        }
    }

    private fun child(
        child: Child,
        out: MutableList<IrStatement>,
    ): IrExpression =
        when (child) {
            is Text -> {
                val text = if (child.value.type.isChar()) b.irCall(symbols.charToString).apply { arguments[0] = child.value } else child.value
                stable(text, "text", out)
            }
            is Element -> b.irGet(local("el", element(child), out))
            is Choice -> b.irGet(local("el", choice(child), out))
        }

    private fun element(element: Element): IrExpression {
        val out = mutableListOf<IrStatement>()
        val type = stable(element.call.arguments[1]!!, "type", out)
        // Created at its first use, or once the children are built, so it stays out of the code that builds them.
        val attrs = lazy {
            local("attrs", b.irImplicitCast(b.irJs(symbols, "({})"), element.propsType), out).also { attrs ->
                element.receiver?.let { substitutions[it] = attrs }
            }
        }
        val children = Children()
        for (item in element.items) {
            if (item.attrsUses > 0) attrs.value
            emit(item, out, children)
        }
        setChildren(attrs.value, children.nodes, out)
        out += jsx(type, b.irGet(attrs.value), element.propsType, children.key ?: element.defaultKey ?: sourceKey(element.call))
        return block(out)
    }

    private fun choice(choice: Choice): IrExpression {
        val branches = choice.branches.map { branch ->
            val out = mutableListOf<IrStatement>()
            out += node(branch.items, out)
            val condition = branch.condition
            if (condition == null) b.irElseBranch(block(out)) else b.irBranch(condition, block(out))
        }
        val fallback = listOfNotNull(b.irElseBranch(b.irNull(symbols.reactNode)).takeIf { choice.branches.last().condition != null })
        return b.irWhen(symbols.reactNode, branches + fallback)
    }

    private fun nodeOf(
        children: List<IrExpression>,
        out: MutableList<IrStatement>,
    ): IrExpression =
        when (children.size) {
            0 -> b.irNull(symbols.reactNode)
            1 -> children.single()
            else -> {
                val attrs = local("attrs", b.irImplicitCast(b.irJs(symbols, "({})"), symbols.fragmentProps), out)
                setChildren(attrs, children, out)
                jsx(b.irCall(symbols.fragment), b.irGet(attrs), attrs.type, null)
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
        props: IrExpression,
        propsType: IrType,
        key: IrExpression?,
    ): IrExpression =
        b.irCall(symbols.jsx, symbols.reactNode).apply {
            typeArguments[0] = propsType
            arguments[0] = type
            arguments[1] = props
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

/** What a list of items adds: its child nodes, in order, and in an element body the explicit `key`. */
private class Children {
    val nodes = mutableListOf<IrExpression>()
    var key: IrExpression? = null

    fun add(node: IrExpression) {
        nodes += node
    }
}

private fun IrExpression.isStable(): Boolean =
    when (this) {
        is IrConst, is IrFunctionExpression, is IrGetObjectValue -> true
        is IrGetValue -> (symbol.owner as? IrVariable)?.isVar != true
        else -> false
    }
