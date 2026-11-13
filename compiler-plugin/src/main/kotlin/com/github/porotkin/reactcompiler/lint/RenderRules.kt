package com.github.porotkin.reactcompiler.lint

import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.isHookName
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isFunctionMarker
import org.jetbrains.kotlin.ir.util.isFunctionTypeOrSubtype
import org.jetbrains.kotlin.ir.util.isGetter
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.isSuspendFunction
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.FqName

/**
 * Checks the code of a file against the Rules of React that are about what code does and when. It walks every
 * function with a stack of [Frame]s telling whose render the code runs in, if any, and whether it runs on every
 * render. The rules, each named after the one of React's linter that it follows:
 *
 * - [LintRule.REACT_RULES_OF_HOOKS]: a hook call sits at the top level of a component or of a hook;
 * - [LintRule.REACT_EXHAUSTIVE_DEPS]: the dependencies of a hook are complete, see [ExhaustiveDeps];
 * - [LintRule.REACT_HOOKS_AS_VALUES]: nothing but a hook is named like one, where React Compiler reads the name.
 */
internal class RenderRules(
    private val file: IrFile,
    private val symbols: ReactSymbols,
    private val lint: LintSymbols,
    private val reporter: LintReporter,
) : IrVisitorVoid() {
    private val frames = mutableListOf(Frame(Scope.OTHER, null))

    /** The scope of each lambda passed to the call being visited, decided by what the callee does with it. */
    private val lambdaScopes = mutableMapOf<IrFunctionExpression, Scope>()

    /** The accessors of `by` locals, which a read or an assignment of the local calls. */
    private val delegated = mutableMapOf<IrSimpleFunctionSymbol, IrLocalDelegatedProperty>()

    /** The hook each local was returned by, which tells the setter of `val (state, setState) = useState()`. */
    private val hookResults = mutableMapOf<IrValueSymbol, String>()

    /** The locals whose value is the same on every render, see [ExhaustiveDeps]. */
    private val stable = mutableSetOf<IrSymbol>()

    private val reportedNames = mutableSetOf<IrValueSymbol>()
    private val dependencies = ExhaustiveDeps(file, lint, reporter, delegated, stable)

    fun check() {
        file.acceptChildrenVoid(this)
    }

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    // Scopes

    override fun visitSimpleFunction(declaration: IrSimpleFunction) {
        val scope = when {
            declaration.name.isHookName() -> Scope.HOOK
            declaration.buildsChildren() || declaration.hasComponentName() -> Scope.COMPONENT
            else -> Scope.OTHER
        }
        inFrame(Frame(scope, declaration)) { declaration.acceptChildrenVoid(this) }
    }

    override fun visitConstructor(declaration: IrConstructor) {
        inFrame(Frame(Scope.OTHER, declaration)) { declaration.acceptChildrenVoid(this) }
    }

    override fun visitClass(declaration: IrClass) {
        inFrame(Frame(Scope.OTHER, null)) { declaration.acceptChildrenVoid(this) }
    }

    override fun visitFunctionExpression(expression: IrFunctionExpression) {
        val function = expression.function
        val scope = lambdaScopes.remove(expression) ?: if (function.isRenderLambda()) Scope.COMPONENT else Scope.CALLBACK
        inFrame(Frame(scope, function)) { function.acceptChildrenVoid(this) }
    }

    override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty) {
        delegated[declaration.getter.symbol] = declaration
        declaration.setter?.let { delegated[it.symbol] = declaration }
        // The accessors only forward to the delegate: what matters is where the local is read and assigned.
        declaration.delegate?.acceptVoid(this)
    }

    override fun visitVariable(declaration: IrVariable) {
        declaration.acceptChildrenVoid(this)
        val value = declaration.initializer ?: return
        val call = value as? IrCall
        val callee = call?.symbol?.owner?.name?.asString()
        if (call != null && call.symbol.owner.name.isHookName()) hookResults[declaration.symbol] = callee!!
        if (declaration.isVar) return
        // As React's linter has it: a constant, a ref, an effect event, and the second of what these hooks return.
        val isSetter = callee == "component2" && (call.receiver() as? IrGetValue)?.symbol?.let(hookResults::get) in HOOKS_RETURNING_SETTERS
        if (value is IrConst || callee in HOOKS_RETURNING_STABLE_VALUES || isSetter) stable += declaration.symbol
    }

    private inline fun inFrame(
        frame: Frame,
        block: () -> Unit,
    ) {
        frames += frame
        block()
        frames.removeLast()
    }

    // Control flow

    override fun visitWhen(expression: IrWhen) {
        expression.branches.forEachIndexed { index, branch ->
            // The first condition is the only part of a `when` that always runs.
            if (index == 0) branch.condition.acceptVoid(this) else conditionally { branch.condition.acceptVoid(this) }
            conditionally { branch.result.acceptVoid(this) }
        }
    }

    private inline fun conditionally(block: () -> Unit) {
        frames.last().branches++
        block()
        frames.last().branches--
    }

    override fun visitLoop(loop: IrLoop) {
        frames.last().loops++
        loop.acceptChildrenVoid(this)
        frames.last().loops--
    }

    override fun visitTry(aTry: IrTry) {
        frames.last().tries++
        aTry.acceptChildrenVoid(this)
        frames.last().tries--
    }

    override fun visitReturn(expression: IrReturn) {
        expression.acceptChildrenVoid(this)
        // Whatever this function runs after a `return`, it does not run every time.
        frames.lastOrNull { it.function?.symbol == expression.returnTargetSymbol }?.returned = true
    }

    /** Where the code being visited runs, found from the innermost frame outwards. */
    private fun site(): Site {
        val site = Site()
        for (frame in frames.asReversed()) {
            site.conditional = site.conditional || frame.branches > 0
            site.repeated = site.repeated || frame.loops > 0
            site.guarded = site.guarded || frame.tries > 0
            site.afterReturn = site.afterReturn || frame.returned
            when (frame.scope) {
                Scope.BLOCK -> continue
                Scope.IN_PLACE -> site.inPlace = true
                Scope.MEMO -> site.memo = true
                Scope.COMPONENT, Scope.HOOK -> return site.apply { root = frame }
                Scope.OTHER, Scope.CALLBACK, Scope.EFFECT -> return site.apply { barrier = frame }
            }
        }
        return site
    }

    /** The component or hook the code being visited is written in, at any depth: a function React Compiler compiles. */
    private fun reactFunction(): Frame? =
        frames.lastOrNull { it.scope == Scope.COMPONENT || it.scope == Scope.HOOK }

    /** The functions whose locals the code being visited can read as values of its own component or hook. */
    private fun localScope(): Set<IrDeclarationParent> =
        buildSet {
            for (frame in frames.asReversed()) {
                frame.function?.let(::add)
                if (!frame.scope.isBlock) break
            }
        }

    // Calls

    override fun visitCall(expression: IrCall) {
        val callee = expression.symbol.owner
        val isHook = callee.name.isHookName()
        classifyLambdas(expression, callee)
        if (isHook || callee.isUse()) checkHookCall(expression, callee)
        if (isHook) dependencies.check(expression, callee, localScope())
        checkHookNamedProperty(expression, callee)
        expression.acceptChildrenVoid(this)
    }

    /**
     * Decides how each lambda argument of [call] runs. The lambdas are visited later, as the arguments they are, so
     * the answers wait in [lambdaScopes].
     */
    private fun classifyLambdas(
        call: IrCall,
        callee: IrSimpleFunction,
    ) {
        call.arguments.forEachIndexed { index, argument ->
            val lambda = argument?.unwrapCasts() as? IrFunctionExpression ?: return@forEachIndexed
            val parameter = callee.parameters.getOrNull(index) ?: return@forEachIndexed
            lambdaScopes[lambda] = scopeOf(call, callee, parameter, lambda.function)
        }
    }

    private fun scopeOf(
        call: IrCall,
        callee: IrSimpleFunction,
        parameter: IrValueParameter,
        lambda: IrSimpleFunction,
    ): Scope {
        if (call.symbol in symbols.componentFactories) return Scope.COMPONENT
        val receiver = lambda.parameters.firstOrNull { it.kind == IrParameterKind.ExtensionReceiver }?.type
        val buildsElement = receiver != null && (receiver.isSubtypeOfClass(symbols.propsClass) || receiver.isSubtypeOfClass(symbols.childrenBuilderClass))
        return when {
            // `div { }`, `Provider(value) { }`: the body of an element being added to a builder.
            buildsElement && call.isOnBuilder(callee) -> Scope.BLOCK
            // A render lambda given to anything else is somebody's component.
            lambda.isRenderLambda() -> Scope.COMPONENT
            // `Component.create { }`: the props of an element created on the spot.
            buildsElement -> Scope.BLOCK
            callee.fqNameWhenAvailable in RUN_ONCE_FUNCTIONS -> Scope.BLOCK
            callee.isInline && parameter.isInlined() -> Scope.IN_PLACE
            call.isUseMemo() -> Scope.MEMO
            callee.fqNameWhenAvailable in EFFECT_HOOKS -> Scope.EFFECT
            else -> Scope.CALLBACK
        }
    }

    private fun IrCall.isUseMemo(): Boolean =
        symbol in symbols.rawHooks && symbol.owner.name.asString() == "useMemo"

    /** Whether [this] call is made on a `ChildrenBuilder`: as its member, its extension, or in its context. */
    private fun IrCall.isOnBuilder(callee: IrSimpleFunction): Boolean =
        callee.parameters.withIndex().any { (index, parameter) ->
            parameter.kind != IrParameterKind.Regular && arguments[index]?.type?.isSubtypeOfClass(symbols.childrenBuilderClass) == true
        }

    private fun IrFunction.buildsChildren(): Boolean =
        parameters.any { it.type.isSubtypeOfClass(symbols.childrenBuilderClass) }

    /** `ChildrenBuilder.(...) -> Unit`: the lambda of a component, as `FC` takes it. */
    private fun IrFunction.isRenderLambda(): Boolean =
        parameters.any { it.kind == IrParameterKind.ExtensionReceiver && it.type.classOrNull == symbols.childrenBuilderClass }

    // Rules of Hooks

    private fun checkHookCall(
        call: IrCall,
        callee: IrSimpleFunction,
    ) {
        val site = site()
        val barrier = site.barrier
        // `use` may be called conditionally, and React's own linter lets it into callbacks.
        val isUse = callee.isUse()
        val problem = when {
            barrier?.scope?.isCallback == true || site.root != null && site.memo ->
                if (isUse || reactFunction() == null) return else "is called in a nested function. Call hooks at the top level of a component or hook, not in callbacks"
            site.root == null -> {
                val function = (barrier?.function as? IrDeclarationWithName)?.name?.takeUnless { it.isSpecial }
                val where = if (function == null) "outside a component or hook" else "in `$function`, which is neither a component nor a hook"
                "is called $where. Call hooks only from components and from functions named `use…`"
            }
            (site.root?.function as? IrSimpleFunction)?.isSuspend == true -> "is called in a suspend function. A hook runs while React renders, which cannot wait"
            site.guarded -> "is called inside try/catch/finally. $EVERY_RENDER"
            isUse -> return
            site.repeated -> "is called in a loop. $EVERY_RENDER"
            site.conditional -> "is called conditionally. $EVERY_RENDER"
            site.afterReturn -> "is called after an early return. $EVERY_RENDER"
            site.inPlace -> "is called in a lambda that may run more than once or not at all. $EVERY_RENDER"
            else -> return
        }
        report(LintRule.REACT_RULES_OF_HOOKS, call, "Hook `${callee.name}` $problem.")
    }

    // Hook names on values

    override fun visitGetValue(expression: IrGetValue) {
        val value = expression.symbol.owner
        val isMisnamed = value.name.isHookName() && !value.type.isFunctionTypeOrSubtype() && reactFunction() != null
        if (isMisnamed && reportedNames.add(expression.symbol)) report(LintRule.REACT_HOOKS_AS_VALUES, value, notAHookMessage("${value.name}"))
    }

    /** A property of an external declaration keeps its name in JavaScript, where a `use` name makes it a hook. */
    private fun checkHookNamedProperty(
        call: IrCall,
        callee: IrSimpleFunction,
    ) {
        val property = callee.correspondingPropertySymbol?.owner ?: return
        if (!property.name.isHookName() || !property.isEffectivelyExternal() || reactFunction() == null) return
        val type = if (callee.isGetter) callee.returnType else callee.parameters.last().type
        if (!type.isFunctionTypeOrSubtype()) report(LintRule.REACT_HOOKS_AS_VALUES, call, notAHookMessage("${property.name}"))
    }

    override fun visitRichFunctionReference(expression: IrRichFunctionReference) {
        // The function a `::reference` invokes through is generated: only the values it is bound to are code of the file.
        if (expression.reflectionTargetSymbol == null) expression.acceptChildrenVoid(this) else expression.boundValues.forEach { it.acceptVoid(this) }
    }

    private fun report(
        rule: LintRule,
        at: IrElement,
        message: String,
    ) {
        reporter.report(rule, file, at, message)
    }
}

/** How the code of a function runs, relative to the function around it. */
private enum class Scope {
    /** The body of a component: an `FC` lambda, or a function that builds children or is named like a component. */
    COMPONENT,

    /** The body of a function named `use…`. */
    HOOK,

    /** Any other named function, a constructor, a class body, or the top level of the file. */
    OTHER,

    /** A lambda that runs once where it is written, as part of the code around it: `run { }`, `div { }`. */
    BLOCK,

    /** A lambda an inline function runs where it is written, any number of times: `forEach { }`. */
    IN_PLACE,

    /** A `useMemo` callback, which React calls while the component renders. */
    MEMO,

    /** The callback of `useEffect` and its kin, which React calls after the component has rendered. */
    EFFECT,

    /** A function that runs later, if at all: an event handler. */
    CALLBACK,
    ;

    /** Whether the code is part of the function it is written in, not a function of its own. */
    val isBlock: Boolean get() = this == BLOCK || this == IN_PLACE

    val isCallback: Boolean get() = this == CALLBACK || this == EFFECT
}

private class Frame(
    val scope: Scope,
    /** The function whose body this is, the target of its `return`s. */
    val function: IrFunction?,
) {
    var branches = 0
    var loops = 0
    var tries = 0
    var returned = false
}

private class Site {
    /** The component or hook whose render runs the code. */
    var root: Frame? = null

    /** The function that keeps the code out of any render, when there is no [root]. */
    var barrier: Frame? = null
    var conditional = false
    var repeated = false

    /** Inside `try`, `catch` or `finally`. */
    var guarded = false
    var afterReturn = false
    var inPlace = false
    var memo = false
}

private const val EVERY_RENDER = "A component or hook must call the same hooks in the same order on every render"

private fun notAHookMessage(name: String): String =
    "`$name` is not a hook, but React Compiler takes every name that starts with `use` for one. Rename it."

private val USE = FqName("react.use")

/** Functions that call their lambda exactly once, where it is written. */
private val SCOPE_FUNCTIONS = setOf("apply", "also", "let", "run", "with", "takeIf", "takeUnless").mapTo(mutableSetOf()) { FqName("kotlin.$it") }
private val RUN_ONCE_FUNCTIONS = SCOPE_FUNCTIONS + setOf(
    "kotlin.collections.buildList",
    "kotlin.collections.buildMap",
    "kotlin.collections.buildSet",
    "kotlin.text.buildString",
    "js.objects.jso",
    "js.objects.unsafeJso",
).map(::FqName)

private val EFFECT_HOOKS = setOf("useEffect", "useLayoutEffect", "useInsertionEffect")
    .flatMapTo(mutableSetOf()) { listOf(FqName("react.$it"), FqName("react.${it}Once")) }

/** The hooks whose result never changes, and those that return a pair whose second part never does. */
private val HOOKS_RETURNING_STABLE_VALUES = setOf("useRef", "useEffectEvent")
private val HOOKS_RETURNING_SETTERS = setOf("useState", "useReducer", "useActionState", "useTransition")

private fun IrSimpleFunction.isUse(): Boolean =
    name.asString() == "use" && fqNameWhenAvailable == USE

/** React's convention: a function is a component when its name starts with a capital letter. */
private fun IrSimpleFunction.hasComponentName(): Boolean =
    !name.isSpecial && name.asString().first().isUpperCase()

/**
 * Whether an inline function inlines the lambda passed for this parameter, and so runs it where it is written. It
 * inlines a parameter of a function type only: a lambda passed for a type parameter, as to `useCallback`, is a value.
 */
private fun IrValueParameter.isInlined(): Boolean =
    !isNoinline && !isCrossinline && (type.isFunction() || type.isSuspendFunction()) && !type.isFunctionMarker()
