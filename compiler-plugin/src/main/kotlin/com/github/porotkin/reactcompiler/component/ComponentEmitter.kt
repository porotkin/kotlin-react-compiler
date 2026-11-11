package com.github.porotkin.reactcompiler.component

import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.irJs
import com.github.porotkin.reactcompiler.irUseMemo
import com.github.porotkin.reactcompiler.irVal
import com.github.porotkin.reactcompiler.isUseMemo
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrFileEntry
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.at
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBranch
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irElseBranch
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfNull
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
import org.jetbrains.kotlin.ir.expressions.IrDynamicOperator
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.impl.IrDynamicMemberExpressionImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrDynamicOperatorExpressionImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.isChar
import org.jetbrains.kotlin.ir.types.isNullable
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.substitute
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Writes an analyzed body back as IR for [fn]: `"use memo"`, the [Plain] statements in order, each element as a
 * `jsx(type, attrs, key)` (or, with several children, `jsxs`) call bound to a local, and a `return` of the root node. Calls to helpers go to the
 * lowered helper [resolveHelper] returns.
 *
 * The nodes built for a piece of the body take its position in the file ([at]), so source maps lead from the
 * generated code back to it.
 */
internal class ComponentEmitter(
    private val fn: IrSimpleFunction,
    private val symbols: ReactSymbols,
    private val b: IrBuilderWithScope,
    private val fileEntry: IrFileEntry,
    private val resolveHelper: (IrSimpleFunctionSymbol) -> IrSimpleFunctionSymbol,
) {
    /** Element body receivers and `let` parameters, mapped to the locals that replace them. */
    private val substitutions = mutableMapOf<IrValueSymbol, IrVariable>()

    private val unitType = b.context.irBuiltIns.unitType

    /** Replaces the body of [fn] with [items], dropping [builderParameter] and returning `ReactNode?`. */
    fun rewrite(
        builderParameter: IrValueParameter,
        items: List<Item>,
    ) {
        fn.parameters -= builderParameter
        fn.returnType = symbols.reactNode
        val out = mutableListOf<IrStatement>(b.irUseMemo(symbols))
        out += returnNode(items.filterNot { it is Plain && it.statement.isUseMemo(symbols) }, out)
        val substitute = object : IrElementTransformerVoid() {
            override fun visitGetValue(expression: IrGetValue): IrExpression =
                substitutions[expression.symbol]?.let { at(expression) { b.irGet(it) } } ?: expression
        }
        val body = fn.body as IrBlockBody
        body.statements.clear()
        out.mapTo(body.statements) { it.transform(substitute, null) as IrStatement }
        body.patchDeclarationParents(fn)
    }

    /** Builds with [b] at the position of [source], the code that the built nodes replace. */
    private inline fun <T> at(
        source: IrElement,
        build: () -> T,
    ): T {
        val start = b.startOffset
        val end = b.endOffset
        b.at(source.startOffset, source.endOffset)
        return build().also { b.at(start, end) }
    }

    /** Emits [items] into [out] and returns the `return` of the node they build, placed where that node is. */
    private fun returnNode(
        items: List<Item>,
        out: MutableList<IrStatement>,
    ): IrExpression {
        val node = node(items, out)
        return at(node) { b.irReturn(node) }
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
            is Rebind -> out += item.variable.apply { initializer = item.initializer }
            is KeyProp -> children.key = stable(item.value, "key", out)
            is Guard -> out += at(item.condition) { guard(item) }
            is Child -> children.add(at(item.source) { child(item, out) })
        }
    }

    /** A return from the component now returns no node. */
    private val returnsNull = object : IrElementTransformerVoid() {
        override fun visitReturn(expression: IrReturn): IrExpression {
            expression.transformChildrenVoid(this)
            return if (expression.returnTargetSymbol == fn.symbol) at(expression) { b.irReturn(b.irNull(symbols.reactNode)) } else expression
        }
    }

    /** `if (condition) { ...; return node }` */
    private fun guard(guard: Guard): IrExpression {
        val then = mutableListOf<IrStatement>()
        then += returnNode(guard.items, then)
        return b.irWhen(unitType, listOf(b.irBranch(guard.condition, b.irBlock(resultType = unitType) { then.forEach { +it } })))
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
            is HelperCall -> b.irGet(local("el", helperCall(child.call), out))
            is ChildWithProps -> b.irGet(local("el", childWithProps(child), out))
            is Element -> b.irGet(local("el", element(child), out))
            is Choice -> b.irGet(local("el", choice(child), out))
            is LetBlock -> {
                substitutions[child.parameter.symbol] = local(child.parameter.name.asString(), child.value, out)
                node(child.items, out)
            }
            is WhileLoop -> {
                val list = local("list", b.irJs(symbols, "([])"), out)
                val body = loopBody(child.items, list)
                child.loop.body = b.irBlock(resultType = unitType) { body.forEach { +it } }
                out += child.loop
                b.irGet(list)
            }
            is RepeatLoop -> {
                val list = local("list", b.irJs(symbols, "([])"), out)
                val body = loopBody(child.items, list)
                (child.action.body as IrBlockBody).statements.apply {
                    clear()
                    addAll(body)
                }
                out += child.call
                b.irGet(list)
            }
        }

    /** Emits [items] as a loop body that pushes each child onto [list]. */
    private fun loopBody(
        items: List<Item>,
        list: IrVariable,
    ): List<IrStatement> {
        val out = mutableListOf<IrStatement>()
        val pushed = object : Children() {
            override fun add(node: IrExpression) {
                out += at(node) { dynamic(IrDynamicOperator.INVOKE, b.irGet(list), "push", node) }
            }
        }
        items.forEach { emit(it, out, pushed) }
        return out
    }

    private fun helperCall(call: IrCall): IrExpression =
        b.irCall(resolveHelper(call.symbol), symbols.reactNode).apply {
            call.typeArguments.forEachIndexed { index, type -> typeArguments[index] = type }
            for (index in 1 until call.arguments.size) {
                arguments[index - 1] = call.arguments[index]
            }
        }

    private fun childWithProps(child: ChildWithProps): IrExpression {
        val out = mutableListOf<IrStatement>()
        val type = stable(child.type, "type", out)
        val props = stable(child.props, "props", out)
        out += jsx(type, props, child.propsType, child.defaultKey)
        return block(out)
    }

    private fun element(element: Element): IrExpression {
        val out = mutableListOf<IrStatement>()
        val rawType = element.componentGetter?.let { getter ->
            val wrapper = element.call.arguments[0]!!
            val wrapperClass = getter.owner.parentAsClass
            val typeArgs = (wrapper.type as? IrSimpleType)?.arguments?.mapNotNull { (it as? IrTypeProjection)?.type }
            val returnType = if (typeArgs != null && typeArgs.size == wrapperClass.typeParameters.size) {
                getter.owner.returnType.substitute(wrapperClass.typeParameters.map { it.symbol }.zip(typeArgs).toMap())
            } else {
                getter.owner.returnType
            }
            b.irCall(getter, returnType).apply { arguments[0] = wrapper }
        } ?: element.call.arguments[1]!!
        val type = stable(rawType, "type", out)
        val props = element.props.map { prop -> PropArg(prop.name, prop.setter, stable(prop.value, prop.name, out)) }
        // Only `provider(value) { }` sets props dynamically by name and has `ChildrenBuilder` as `element.propsType`.
        val attrsType = if (element.propsType.isSubtypeOfClass(symbols.propsClass)) element.propsType else symbols.fragmentProps
        // Created at its first use, or once the children are built, so it stays out of the code that builds them.
        val attrs = lazy {
            local("attrs", b.irImplicitCast(b.irJs(symbols, "({})"), attrsType), out).also { attrs ->
                element.receiver?.let { substitutions[it] = attrs }
                for (prop in props) {
                    out += if (prop.setter != null) {
                        b.irCall(prop.setter).apply {
                            arguments[0] = b.irGet(attrs)
                            arguments[1] = prop.value
                        }
                    } else {
                        dynamic(IrDynamicOperator.EQ, b.irGet(attrs), prop.name, prop.value)
                    }
                }
            }
        }
        if (props.isNotEmpty()) attrs.value
        val children = Children()
        for (item in element.items) {
            if (item.attrsUses > 0) attrs.value
            emit(item, out, children)
        }
        setChildren(attrs.value, children.nodes, out)
        val key = children.key ?: element.defaultKey ?: sourceKey(element.call)
        out += jsx(type, b.irGet(attrs.value), attrsType, key, staticChildren = children.nodes.size > 1)
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
                jsx(b.irCall(symbols.fragment), b.irGet(attrs), attrs.type, null, staticChildren = true)
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

    /**
     * `jsx(type, props, key)`, or, when [props] has [staticChildren] set as an array, React's `jsxs`, which kotlin-react
     * also calls for several children: `jsx` would check the array for keys as a list.
     */
    private fun jsx(
        type: IrExpression,
        props: IrExpression,
        propsType: IrType,
        key: IrExpression?,
        staticChildren: Boolean = false,
    ): IrExpression =
        if (!staticChildren) {
            b.irCall(symbols.jsx, symbols.reactNode).apply {
                typeArguments[0] = propsType
                arguments[0] = type
                arguments[1] = props
                // An unset argument means "omitted", which fails to link: kotlin-react's `jsx` has no default `defaultKey`.
                arguments[2] = key ?: b.irNull()
            }
        } else {
            b.irCall(symbols.jsxs, symbols.reactNode).apply {
                arguments[0] = type
                arguments[1] = props
                // React keys an element "null" for a null key, so pass none instead, as kotlin-react's `jsx` does. A
                // nullable key is an explicit `key = ...`, which [stable] made safe to read twice.
                arguments[2] = key?.takeIf { it.type.isNullable() }
                    ?.let { b.irIfNull(it.type, it, b.irJs(symbols, "undefined"), it.deepCopyWithSymbols()) }
                    ?: key
            }
        }

    /** `target.member = argument` or `target.member(argument)` on a JS object. */
    private fun dynamic(
        operator: IrDynamicOperator,
        target: IrExpression,
        member: String,
        argument: IrExpression,
    ): IrExpression =
        IrDynamicOperatorExpressionImpl(
            startOffset = b.startOffset,
            endOffset = b.endOffset,
            type = unitType,
            operator = operator,
        ).apply {
            receiver = IrDynamicMemberExpressionImpl(
                startOffset = b.startOffset,
                endOffset = b.endOffset,
                type = symbols.js.owner.returnType,
                memberName = member,
                receiver = target,
            )
            arguments += argument
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
private open class Children {
    val nodes = mutableListOf<IrExpression>()
    var key: IrExpression? = null

    open fun add(node: IrExpression) {
        nodes += node
    }
}

private fun IrExpression.isStable(): Boolean =
    when (this) {
        is IrConst, is IrFunctionExpression, is IrGetObjectValue -> true
        is IrGetValue -> (symbol.owner as? IrVariable)?.isVar != true
        else -> false
    }
