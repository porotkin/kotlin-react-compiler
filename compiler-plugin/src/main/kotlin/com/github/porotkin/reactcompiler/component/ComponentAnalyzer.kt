package com.github.porotkin.reactcompiler.component

import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.any
import com.github.porotkin.reactcompiler.count
import com.github.porotkin.reactcompiler.unwrapCoercion
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrComposite
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrElseBranch
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.expressions.IrWhileLoop
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.util.substitute
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.FqName

private val ELEMENT_BUILDER = FqName("react.ElementBuilder")
private val SET_DEFAULT_KEY = FqName("react.setDefaultKey")
private val KOTLIN_REPEAT = FqName("kotlin.repeat")
private val KOTLIN_LET = FqName("kotlin.let")

/**
 * Reads a body written against kotlin-react's `ChildrenBuilder` DSL into the [Item] model, reading calls to
 * [helpers] on the builder as children. [analyze] returns `null` when the body uses the builder in a shape
 * [ComponentEmitter] cannot reproduce, which leaves the body untouched.
 */
internal class ComponentAnalyzer(
    private val symbols: ReactSymbols,
    private val helpers: Set<IrSimpleFunctionSymbol>,
) {
    /** Reads the body of [function], whose parameter [builder] is the `ChildrenBuilder`. */
    fun analyze(
        function: IrFunction,
        builder: IrValueSymbol,
    ): List<Item>? {
        val statements = (function.body as? IrBlockBody)?.statements ?: return null
        val scope = Scope(builder, function.symbol, isRoot = true, isTail = true, allowAttrs = false, isElementBody = false)
        return scope.analyze(statements)
    }

    /**
     * The statements written against one builder [receiver] in a function [owner]: the component body ([isRoot]),
     * an element body, or a branch, loop body or inline lambda nested in one. The last statement of an [isTail]
     * scope ends the body, so it may return. Where [allowAttrs], reads of the receiver as props access the attrs
     * object of the element; in an [isElementBody], `key = ...` becomes the element's `jsx` key.
     */
    private inner class Scope(
        private val receiver: IrValueSymbol,
        private val owner: IrFunctionSymbol,
        private val isRoot: Boolean,
        private val isTail: Boolean,
        private val allowAttrs: Boolean,
        private val isElementBody: Boolean,
    ) {
        fun analyze(statements: List<IrStatement>): List<Item>? {
            val trimmed = statements.trimTrailingReturn().flattenBlocks()
            val pending = ArrayDeque(trimmed)
            val items = mutableListOf<Item>()
            while (pending.isNotEmpty()) {
                val statement = pending.removeFirst()
                val atTail = isTail && pending.isEmpty()
                if (statement.returnsTo(owner)) {
                    if (isRoot && items.none { it is Child } && statement.uses(receiver) == 0) {
                        // e.g. `if (!visible) return@FC` before anything is built: returns `null`.
                        items += plain(statement)
                        continue
                    }
                    val early = earlyReturn(statement)
                    if (early == null) {
                        // Only the statement ending the body may return in a shape not split here.
                        if (!atTail) return null
                    } else {
                        items += early.preamble.map(::plain)
                        val then = subScope(isTail = false).analyze(early.then) ?: return null
                        val rebind = early.rebind?.let { (variable, value) -> Rebind(variable, value, attrsUses(value)) }
                        if (isRoot && items.none { it is Child }) {
                            // Statements past a root early return stay at the top level, where React Compiler needs hooks.
                            items += Guard(early.condition, then)
                            items += listOfNotNull(rebind)
                            pending.addAll(0, early.orElse.flattenBlocks())
                            continue
                        }
                        val orElse = subScope(isTail).analyze(early.orElse + pending) ?: return null
                        val branches = listOf(Branch(early.condition, then), Branch(null, listOfNotNull(rebind) + orElse))
                        items += Choice(statement, branches, attrsUses(early.condition))
                        break
                    }
                }
                items += classify(statement, atTail) ?: return null
            }
            // A receiver read no item accounts for (e.g. the builder escaping into a function) makes the body unsafe to rewrite.
            return items.takeIf { trimmed.sumOf { it.uses(receiver) } == items.sumOf(Item::receiverUses) }
        }

        private fun classify(
            statement: IrStatement,
            atTail: Boolean,
        ): Item? {
            val s = statement.unwrapCoercion()
            if (s is IrComposite && s.isKeyedElement()) {
                val (setKey, call) = s.statements.map { it.unwrapCoercion() as IrCall }
                return if (call.isElementInvoke()) element(call, setKey.arguments[1]) else childWithProps(call, setKey.arguments[1])
            }
            if (s is IrCall && s.isElementInvoke()) return element(s, null)
            if (s is IrCall && s.isOnReceiver()) {
                when {
                    s.isUnaryPlus() -> return Text(s.arguments[1]!!)
                    s.isChildWithProps() -> return childWithProps(s, null)
                    s.symbol in helpers -> return HelperCall(s)
                    isElementBody && s.isKeySetter() && s.arguments.last()!!.uses(receiver) == 0 -> return KeyProp(s.arguments.last()!!)
                }
            }
            val structure = when {
                s is IrWhen -> choice(s, atTail)
                s is IrWhileLoop -> whileLoop(s)
                s is IrCall && s.isInlineBlockCall(KOTLIN_REPEAT) -> repeatLoop(s)
                s is IrCall && s.isInlineBlockCall(KOTLIN_LET) -> letBlock(s)
                else -> null
            }
            // A structure that adds no children, or one that cannot be read, stays a plain statement. If it does add
            // children, its builder reads are left unaccounted for, which rejects the whole body.
            return structure?.takeIf { it.addsChildren } ?: plain(statement)
        }

        private fun plain(statement: IrStatement): Plain =
            Plain(statement, attrsUses(statement))

        private fun subScope(
            isTail: Boolean,
            owner: IrFunctionSymbol = this.owner,
        ): Scope =
            Scope(receiver, owner, isRoot = false, isTail, allowAttrs, isElementBody = false)

        private fun element(
            call: IrCall,
            defaultKey: IrExpression?,
        ): Element? {
            val wrapper = if (call.isOnReceiver()) null else ElementWrappers.resolve(call.symbol.owner, symbols) ?: return null
            val receiverIndex = if (wrapper == null) 0 else 1
            // The type, props and body move into code of their own, so they must not read this builder, use defaults, or pass varargs.
            if (call.arguments.withIndex().any { (index, arg) -> arg == null || arg is IrVararg || index != receiverIndex && arg.uses(receiver) != 0 }) return null
            if (call.arguments.size == 2) {
                return Element(call, null, defaultKey, emptyList(), null, call.typeArguments[0] ?: return null, emptyList())
            }
            val block = (call.arguments.last() as? IrFunctionExpression)?.function ?: return null
            val bodyReceiver = block.parameters.singleOrNull() ?: return null
            val props = (2 until call.arguments.lastIndex).map { index ->
                PropArg(
                    name = call.symbol.owner.parameters[index].name.asString(),
                    setter = wrapper?.propSetters?.getOrNull(index - 2),
                    value = call.arguments[index] ?: return null,
                )
            }
            val propsType = if (wrapper != null) {
                val wrapperClass = wrapper.componentGetter.owner.parentAsClass
                val typeArgs = (call.arguments[0]?.type as? IrSimpleType)?.arguments?.mapNotNull { (it as? IrTypeProjection)?.type }
                if (typeArgs != null && typeArgs.size == wrapperClass.typeParameters.size) {
                    wrapper.propsType.substitute(wrapperClass.typeParameters.map { it.symbol }.zip(typeArgs).toMap())
                } else {
                    wrapper.propsType
                }
            } else {
                bodyReceiver.type
            }
            // A `provider(value) { }` or `@ElementWrapper` body only adds children; a `type { }` body may also set props on the attrs object.
            val allowAttrs = props.isEmpty() && bodyReceiver.type.classOrNull != symbols.childrenBuilderClass
            val items = Scope(bodyReceiver.symbol, block.symbol, isRoot = false, isTail = true, allowAttrs, isElementBody = allowAttrs)
                .analyze(block.blockStatements() ?: return null) ?: return null
            return Element(call, wrapper?.componentGetter, defaultKey, props, bodyReceiver.symbol, propsType, items)
        }

        private fun childWithProps(
            call: IrCall,
            defaultKey: IrExpression?,
        ): ChildWithProps? {
            val type = call.arguments[1] ?: return null
            val props = call.arguments[2] ?: return null
            if (type.uses(receiver) != 0 || props.uses(receiver) != 0) return null
            return ChildWithProps(call, type, props, defaultKey, call.typeArguments[0]!!)
        }

        /** An `if` / `when`; only one ending the body ([atTail]) may return, and then its branches end it too. */
        private fun choice(
            expression: IrWhen,
            atTail: Boolean,
        ): Choice? {
            if (expression.branches.any { it.condition.returnsTo(owner) }) return null
            if (!atTail && expression.returnsTo(owner)) return null
            val branches = expression.branches.map { branch ->
                val items = subScope(atTail).analyze(branch.result.branchStatements()) ?: return null
                Branch(branch.condition.takeUnless { branch is IrElseBranch }, items)
            }
            return Choice(expression, branches, expression.branches.sumOf { attrsUses(it.condition) })
        }

        private fun whileLoop(loop: IrWhileLoop): WhileLoop? {
            if (loop.returnsTo(owner)) return null
            val items = subScope(isTail = false).analyze(loop.body?.branchStatements() ?: return null) ?: return null
            return WhileLoop(loop, items, attrsUses(loop.condition))
        }

        private fun repeatLoop(call: IrCall): RepeatLoop? {
            val times = call.arguments[0] ?: return null
            val action = (call.arguments[1] as IrFunctionExpression).function
            if (action.returnsTo(owner) || times.returnsTo(owner) || times.uses(receiver) != 0) return null
            val items = subScope(isTail = true, action.symbol).analyze(action.blockStatements() ?: return null) ?: return null
            return RepeatLoop(call, action, items)
        }

        private fun letBlock(call: IrCall): LetBlock? {
            val value = call.arguments[0] ?: return null
            val action = (call.arguments[1] as IrFunctionExpression).function
            val parameter = action.parameters.singleOrNull() ?: return null
            if (action.returnsTo(owner) || value.returnsTo(owner) || value.uses(receiver) != 0) return null
            val items = subScope(isTail = true, action.symbol).analyze(action.blockStatements() ?: return null) ?: return null
            return LetBlock(value, parameter, items)
        }

        /** `if (cond) { ...; return } [else { ... }]`, or `val v = x ?: run { ...; return }`: locals ending in such an `if`. */
        private fun earlyReturn(statement: IrStatement): EarlyReturn? =
            when (val s = statement.unwrapCoercion()) {
                is IrWhen -> {
                    val (then, orElse) = s.returnBranch() ?: return null
                    // The `else` value is dropped in statement position, e.g. the `tmp` of a flattened `x ?: return`.
                    EarlyReturn(emptyList(), s.branches[0].condition, then, orElse?.branchStatements().orEmpty().filterNot { it is IrGetValue }, null)
                }
                is IrVariable -> {
                    val block = s.initializer as? IrContainerExpression ?: return null
                    val ifReturn = block.statements.lastOrNull() as? IrWhen ?: return null
                    val preamble = block.statements.dropLast(1)
                    if (preamble.any { it !is IrVariable || it.returnsTo(owner) }) return null
                    val (then, orElse) = ifReturn.returnBranch() ?: return null
                    EarlyReturn(preamble, ifReturn.branches[0].condition, then, emptyList(), s to (orElse ?: return null))
                }
                else -> null
            }

        /** The statements before the `return` of `if (cond) { ...; return } [else value]`, and the `else` value. */
        private fun IrWhen.returnBranch(): Pair<List<IrStatement>, IrExpression?>? {
            val first = branches.firstOrNull()?.takeUnless { it is IrElseBranch } ?: return null
            if (branches.size > 2) return null
            val orElse = branches.getOrNull(1)?.let { it as? IrElseBranch ?: return null }?.result
            val statements = first.result.branchStatements()
            val last = statements.lastOrNull() as? IrReturn ?: return null
            if (last.returnTargetSymbol != owner || !last.isUnitReturn()) return null
            val then = statements.dropLast(1)
            if (then.any { it.returnsTo(owner) } || first.condition.returnsTo(owner) || orElse?.returnsTo(owner) == true) return null
            return then to orElse
        }

        /** `type { }` / `type()` / `provider(value) { }` / `wrapper(props...) { }`: an element `invoke` on this scope's builder. */
        private fun IrCall.isElementInvoke(): Boolean =
            symbol.owner.name.asString() == "invoke" &&
                (
                    arguments.size in 2..4 &&
                        isOnReceiver() &&
                        arguments[1]?.type?.isSubtypeOfClass(symbols.elementTypeClass) == true &&
                        symbol.owner.allOverridden(includeSelf = true).any { it.hasAnnotation(ELEMENT_BUILDER) } ||
                        (arguments.getOrNull(1) as? IrGetValue)?.symbol == receiver &&
                        ElementWrappers.resolve(symbol.owner, symbols) != null
                )

        private fun IrCall.isUnaryPlus(): Boolean =
            isChildrenBuilderMember("unaryPlus", arity = 2)

        private fun IrCall.isChildWithProps(): Boolean =
            isChildrenBuilderMember("child", arity = 3)

        private fun IrCall.isChildrenBuilderMember(
            name: String,
            arity: Int,
        ): Boolean =
            symbol.owner.name.asString() == name &&
                arguments.size == arity &&
                symbol.owner.allOverridden(includeSelf = true).any { it.parentClassOrNull?.symbol == symbols.childrenBuilderClass }

        private fun IrCall.isOnReceiver(): Boolean =
            (arguments.getOrNull(0) as? IrGetValue)?.symbol == receiver

        /** `setDefaultKey(key)` followed by the element or `child(type, props)` it keys. */
        private fun IrContainerExpression.isKeyedElement(): Boolean {
            if (this !is IrComposite || statements.size != 2) return false
            val setKey = statements[0].unwrapCoercion() as? IrCall ?: return false
            val call = statements[1].unwrapCoercion() as? IrCall ?: return false
            return setKey.symbol.owner.fqNameWhenAvailable == SET_DEFAULT_KEY &&
                setKey.isOnReceiver() &&
                (call.isElementInvoke() || call.isOnReceiver() && call.isChildWithProps())
        }

        /** Reads of the receiver passed where props are expected: attrs accesses, when this scope [allowAttrs]. */
        private fun attrsUses(element: IrElement): Int {
            if (!allowAttrs) return 0
            var uses = 0
            element.acceptVoid(
                object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) {
                        element.acceptChildrenVoid(this)
                    }

                    override fun visitCall(expression: IrCall) {
                        expression.arguments.forEachIndexed { index, argument ->
                            if ((argument?.unwrapImplicitCast() as? IrGetValue)?.symbol == receiver && isAttrsUse(expression, index)) uses++
                        }
                        super.visitCall(expression)
                    }
                },
            )
            return uses
        }

        private fun isAttrsUse(
            call: IrCall,
            index: Int,
        ): Boolean {
            val function = call.symbol.owner
            val type = function.parameters.getOrNull(index)?.type ?: return false
            if (type.classifierOrNull is IrTypeParameterSymbol) return false
            return if (function.correspondingPropertySymbol != null && index == 0) {
                type.classOrNull != symbols.childrenBuilderClass && type.classOrNull != symbols.propsWithChildrenClass
            } else {
                type.isSubtypeOfClass(symbols.propsClass) && !type.isSubtypeOfClass(symbols.childrenBuilderClass)
            }
        }

        private tailrec fun IrExpression.unwrapImplicitCast(): IrExpression =
            if (this is IrTypeOperatorCall && operator == IrTypeOperator.IMPLICIT_CAST && type.isSubtypeOfClass(symbols.propsClass)) {
                argument.unwrapImplicitCast()
            } else {
                this
            }

        private fun List<IrStatement>.flattenBlocks(): List<IrStatement> =
            flatMap { statement ->
                val s = statement.unwrapCoercion()
                if (s is IrContainerExpression && !s.isKeyedElement()) s.statements.flattenBlocks() else listOf(statement)
            }

        private fun IrExpression.branchStatements(): List<IrStatement> {
            val s = unwrapCoercion()
            return if (s is IrContainerExpression && !s.isKeyedElement()) s.statements else listOf(this)
        }

        private fun List<IrStatement>.trimTrailingReturn(): List<IrStatement> {
            val last = lastOrNull() as? IrReturn ?: return this
            if (last.returnTargetSymbol != owner) return this
            return if (last.isUnitReturn()) dropLast(1) else dropLast(1) + last.value
        }
    }
}

private class EarlyReturn(
    /** Locals evaluated before [condition]. */
    val preamble: List<IrStatement>,
    val condition: IrExpression,
    /** The statements before the `return`. */
    val then: List<IrStatement>,
    /** The statements run when [condition] is false. */
    val orElse: List<IrStatement>,
    /** For `val v = x ?: run { ...; return }`, `v` and its value when [condition] is false. */
    val rebind: Pair<IrVariable, IrExpression>?,
)

private fun IrFunction.blockStatements(): List<IrStatement>? =
    (body as? IrBlockBody)?.statements

private fun IrCall.isInlineBlockCall(function: FqName): Boolean =
    symbol.owner.fqNameWhenAvailable == function && arguments.size == 2 && arguments[1] is IrFunctionExpression

private fun IrCall.isKeySetter(): Boolean {
    val property = symbol.owner.correspondingPropertySymbol?.owner ?: return false
    return property.setter == symbol.owner && property.name.asString() == "key"
}

private fun IrReturn.isUnitReturn(): Boolean =
    value.let { it is IrGetObjectValue && it.type.isUnit() }

private fun IrElement.returnsTo(owner: IrFunctionSymbol): Boolean =
    any { it is IrReturn && it.returnTargetSymbol == owner }

private fun IrElement.uses(receiver: IrValueSymbol): Int =
    count { it is IrGetValue && it.symbol == receiver }
