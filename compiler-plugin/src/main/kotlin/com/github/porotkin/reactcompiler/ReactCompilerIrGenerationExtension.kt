package com.github.porotkin.reactcompiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.builders.irAnnotation
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
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrElseBranch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isChar
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.isJs

private val ELEMENT_BUILDER = FqName("react.ElementBuilder")
private val CHILDREN_BUILDER = FqName("react.ChildrenBuilder")
private val SET_DEFAULT_KEY = FqName("react.setDefaultKey")
private val HOOK_NAME = Regex("^use[A-Z0-9]")
private const val USE_MEMO = "\"use memo\""

class ReactCompilerIrGenerationExtension : IrGenerationExtension {
    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
    ) {
        if (!pluginContext.platform.isJs()) return
        val file = moduleFragment.files.firstOrNull() ?: return
        val symbols = ReactSymbols.resolve(pluginContext.finderForSource(file)) ?: return
        val transformer = ReactCompilerTransformer(pluginContext, symbols)
        moduleFragment.files.forEach { it.transform(transformer, null) }
    }
}

private sealed interface Item

private class Plain(
    val statement: IrStatement,
) : Item

private class Prop(
    val setter: IrCall,
) : Item

private class Slot(
    val child: Child,
) : Item

private sealed interface Child

private class Text(
    val value: IrExpression,
) : Child

private class Element(
    val call: IrCall,
    val defaultKey: IrExpression?,
    val propsType: IrType,
    val items: List<Item>,
) : Child

private class Choice(
    val expression: IrWhen,
    val branches: List<List<Item>>,
) : Child

private class ReactCompilerTransformer(
    private val context: IrPluginContext,
    private val symbols: ReactSymbols,
) : IrElementTransformerVoid() {
    private val components = mutableSetOf<IrExpression>()
    private lateinit var file: IrFile

    override fun visitFile(declaration: IrFile): IrFile {
        file = declaration
        return super.visitFile(declaration)
    }

    override fun visitProperty(declaration: IrProperty): IrStatement {
        declaration.transformChildrenVoid(this)
        val initializer = declaration.backingField?.initializer
        val component = components.firstOrNull { initializer?.any { node -> node == it } == true }
        if (component != null && initializer != null) {
            initializer.expression = component
            declaration.annotations += builder(declaration.symbol).irAnnotation(symbols.eagerInitialization)
        }
        return declaration
    }

    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
        declaration.transformChildrenVoid(this)
        val body = declaration.body as? IrBlockBody
        if (
            body != null &&
            !declaration.isInline &&
            HOOK_NAME.containsMatchIn(declaration.name.asString()) &&
            body.statements.firstOrNull()?.isDirective() != true
        ) {
            body.statements.add(0, builder(declaration.symbol).jsCode(USE_MEMO))
        }
        return declaration
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)
        return when (expression.symbol) {
            in symbols.componentFactories -> lowerComponent(expression)
            in symbols.rawHooks -> rewriteHook(expression)
            else -> null
        } ?: expression
    }

    private fun rewriteHook(call: IrCall): IrExpression? {
        val dependencies = call.arguments[0] as IrVararg?
        if (dependencies?.elements?.any { it is IrSpreadElement } == true) return null
        val b = builder(call.symbol)
        return b.irCall(symbols.rawHooks.getValue(call.symbol), call.type).apply {
            call.typeArguments.forEachIndexed { index, type -> typeArguments[index] = type }
            arguments[0] = call.arguments[1]
            arguments[1] = dependencies ?: b.irVararg(context.irBuiltIns.anyNType, emptyList())
        }
    }

    private fun lowerComponent(call: IrCall): IrExpression? {
        val lambda = call.arguments[0] as? IrFunctionExpression ?: return null
        val fn = lambda.function
        val body = fn.body as? IrBlockBody ?: return null
        val builderParameter = fn.parameters.singleOrNull { it.kind == IrParameterKind.ExtensionReceiver } ?: return null
        val items = analyzeBlock(body.statements, builderParameter.symbol, fn.symbol, root = true) ?: return null

        fn.parameters -= builderParameter
        fn.returnType = symbols.reactNode
        lambda.type = context.irBuiltIns.functionN(fn.parameters.size)
            .typeWith(fn.parameters.map { it.type } + symbols.reactNode)

        val statements = ComponentEmitter(fn).emit(items)
        body.statements.clear()
        body.statements += statements
        body.patchDeclarationParents(fn)

        return builder(fn.symbol).irImplicitCast(lambda, call.type).also(components::add)
    }

    private inner class ComponentEmitter(
        private val fn: IrSimpleFunction,
    ) {
        private val b = builder(fn.symbol)

        fun emit(items: List<Item>): List<IrStatement> {
            val out = mutableListOf<IrStatement>(b.jsCode(USE_MEMO))
            val returnsNull = object : IrElementTransformerVoid() {
                override fun visitReturn(expression: IrReturn): IrExpression {
                    expression.transformChildrenVoid(this)
                    return if (expression.returnTargetSymbol == fn.symbol) b.irReturn(b.irNull(symbols.reactNode)) else expression
                }
            }
            val children = emitItems(
                items = items
                    .filterNot { it is Plain && it.statement.isDirective() }
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
            val attrs = local("attrs", b.irImplicitCast(b.jsCode("({})"), element.propsType), out)
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
                    val attrs = local("attrs", b.irImplicitCast(b.jsCode("({})"), symbols.fragmentProps), out)
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
                arguments[2] = key
            }

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
            buildVariable(fn, UNDEFINED_OFFSET, UNDEFINED_OFFSET, IrDeclarationOrigin.DEFINED, Name.identifier(name), value.type)
                .apply { initializer = value }
                .also(out::add)

        private fun block(statements: List<IrStatement>): IrExpression =
            b.irBlock(resultType = symbols.reactNode) { statements.forEach { +it } }

        private fun sourceKey(call: IrCall): IrExpression? {
            if (call.startOffset < 0) return null
            val entry = file.fileEntry
            return b.irString("@rdk/${entry.getLineNumber(call.startOffset)}_${entry.getColumnNumber(call.startOffset)}")
        }
    }

    private fun builder(symbol: IrSymbol) =
        DeclarationIrBuilder(context, symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)

    private fun DeclarationIrBuilder.jsCode(code: String): IrExpression =
        irCall(symbols.js).apply { arguments[0] = irString(code) }

    private fun IrStatement.isDirective(): Boolean {
        val call = unwrapCoercion() as? IrCall ?: return false
        return call.symbol == symbols.js && (call.arguments[0] as? IrConst)?.value == USE_MEMO
    }
}

private fun analyzeBlock(
    statements: List<IrStatement>,
    receiver: IrValueSymbol,
    owner: IrFunctionSymbol,
    root: Boolean,
): List<Item>? {
    val trimmed = statements.trimTrailingReturn(owner)
    val items = mutableListOf<Item>()
    for (statement in trimmed) {
        val item = classify(statement, receiver, owner) ?: return null
        if (item is Prop && root) return null
        if (item is Plain && statement.returnsTo(owner) && (!root || items.any { it is Slot })) return null
        items += item
    }
    return items.takeIf { trimmed.sumOf { it.uses(receiver) } == items.sumOf { it.receiverUses() } }
}

private fun classify(
    statement: IrStatement,
    receiver: IrValueSymbol,
    owner: IrFunctionSymbol,
): Item? {
    val s = statement.unwrapCoercion()
    if (s is IrComposite && s.statements.size == 2) {
        val setKey = s.statements[0].unwrapCoercion() as? IrCall
        val invoke = s.statements[1].unwrapCoercion() as? IrCall
        if (
            setKey != null && invoke != null &&
            setKey.symbol.owner.fqNameWhenAvailable == SET_DEFAULT_KEY &&
            setKey.isOn(receiver) && invoke.isOn(receiver) && invoke.isElementInvoke()
        ) {
            return element(invoke, setKey.arguments[1])?.let(::Slot)
        }
    }
    if (s is IrCall && s.isOn(receiver)) {
        return when {
            s.isElementInvoke() -> element(s, null)?.let(::Slot)
            s.isUnaryPlus() -> Slot(Text(s.arguments[1]!!)).takeUnless { s.symbol.owner.parameters[1].type.isChar() }
            s.isPropSetter() -> Prop(s)
            else -> null
        }
    }
    if (s is IrWhen && s.isChildOn(receiver)) return choice(s, receiver, owner)?.let(::Slot)
    return Plain(statement)
}

private fun element(
    call: IrCall,
    defaultKey: IrExpression?,
): Element? {
    if (call.arguments.size == 2) return Element(call, defaultKey, call.typeArguments[0]!!, emptyList())
    val block = (call.arguments.getOrNull(2) as? IrFunctionExpression)?.function ?: return null
    val receiver = block.parameters.singleOrNull() ?: return null
    val statements = (block.body as? IrBlockBody)?.statements ?: return null
    val items = analyzeBlock(statements, receiver.symbol, block.symbol, root = false) ?: return null
    return Element(call, defaultKey, receiver.type, items)
}

private fun choice(
    expression: IrWhen,
    receiver: IrValueSymbol,
    owner: IrFunctionSymbol,
): Choice? {
    if (expression.returnsTo(owner)) return null
    val branches = expression.branches.map { branch ->
        val items = branch.result.branchStatements().map { classify(it, receiver, owner) ?: return null }
        if (items.any { it is Prop } || items.count { it is Slot } > 1) return null
        items
    }
    return Choice(expression, branches)
}

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

private fun IrStatement.isChildOn(receiver: IrValueSymbol): Boolean =
    when (val s = unwrapCoercion()) {
        is IrComposite -> s.statements.any { it.isChildOn(receiver) }
        is IrCall -> s.isOn(receiver) && (s.isElementInvoke() || s.isUnaryPlus())
        is IrWhen -> s.branches.any { branch -> branch.result.branchStatements().any { it.isChildOn(receiver) } }
        else -> false
    }

private fun IrCall.isOn(receiver: IrValueSymbol): Boolean =
    (arguments.getOrNull(0) as? IrGetValue)?.symbol == receiver

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

private fun IrCall.propertyName(): String =
    symbol.owner.correspondingPropertySymbol!!.owner.name.asString()

private fun IrExpression.isStable(): Boolean =
    when (this) {
        is IrConst, is IrFunctionExpression, is IrGetObjectValue -> true
        is IrGetValue -> (symbol.owner as? IrVariable)?.isVar != true
        else -> false
    }

private fun IrExpression.branchStatements(): List<IrStatement> =
    (this as? IrBlock)?.statements ?: listOf(this)

private fun IrStatement.unwrapCoercion(): IrStatement =
    if (this is IrTypeOperatorCall && operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) argument else this

private fun List<IrStatement>.trimTrailingReturn(owner: IrFunctionSymbol): List<IrStatement> {
    val last = lastOrNull() as? IrReturn ?: return this
    if (last.returnTargetSymbol != owner) return this
    val value = last.value
    return if (value is IrGetObjectValue && value.type.isUnit()) dropLast(1) else dropLast(1) + value
}

private fun IrStatement.returnsTo(owner: IrFunctionSymbol): Boolean =
    any { it is IrReturn && it.returnTargetSymbol == owner }

private fun IrStatement.uses(receiver: IrValueSymbol): Int {
    var count = 0
    acceptVoid(
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (element is IrGetValue && element.symbol == receiver) count++
                element.acceptChildrenVoid(this)
            }
        },
    )
    return count
}

private fun IrElement.any(predicate: (IrElement) -> Boolean): Boolean {
    var found = false
    acceptVoid(
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (found) return
                if (predicate(element)) found = true else element.acceptChildrenVoid(this)
            }
        },
    )
    return found
}
