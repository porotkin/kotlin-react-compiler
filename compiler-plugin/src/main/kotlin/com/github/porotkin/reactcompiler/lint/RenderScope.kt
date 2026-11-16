package com.github.porotkin.reactcompiler.lint

import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.isComponentName
import com.github.porotkin.reactcompiler.isHookName
import com.github.porotkin.reactcompiler.isOnBuilder
import com.github.porotkin.reactcompiler.optsOutOfMemo
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.symbols.IrReturnTargetSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isFunctionMarker
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.isSuspendFunction
import org.jetbrains.kotlin.name.FqName

/**
 * Tracks whose render the code being visited runs in, if any, and whether it runs on every render. Shared by the
 * render-time lint checkers walked by [RenderWalk].
 */
internal class RenderScope(
    private val symbols: ReactSymbols,
) {
    private val frames = mutableListOf(Frame(Scope.OTHER, null))

    /** The scope of each lambda passed to the call being visited, decided by what the callee does with it. */
    private val lambdaScopes = mutableMapOf<IrFunctionExpression, Scope>()

    private val delegatedProperties = mutableMapOf<IrSimpleFunctionSymbol, IrLocalDelegatedProperty>()

    /** The accessors of `by` locals, mapped to the local delegated property they read or assign. */
    val delegated: Map<IrSimpleFunctionSymbol, IrLocalDelegatedProperty> get() = delegatedProperties

    fun frameFor(declaration: IrSimpleFunction): Frame {
        val scope = when {
            declaration.name.isHookName() -> Scope.HOOK
            declaration.buildsChildren() || declaration.name.isComponentName() -> Scope.COMPONENT
            else -> Scope.OTHER
        }
        // A capitalized function is a component by its name only, so it is held to the rules of render only if it takes props.
        val renders = when (scope) {
            Scope.HOOK -> true
            Scope.COMPONENT -> declaration.buildsChildren() || declaration.parameters.any { it.type.isSubtypeOfClass(symbols.propsClass) }
            else -> false
        }
        return Frame(scope, declaration, renders, declaration.optsOutOfMemo(symbols))
    }

    fun frameFor(expression: IrFunctionExpression): Frame {
        val function = expression.function
        val scope = lambdaScopes.remove(expression) ?: if (function.isRenderLambda()) Scope.COMPONENT else Scope.CALLBACK
        return Frame(scope, function, scope == Scope.COMPONENT, function.optsOutOfMemo(symbols))
    }

    inline fun inFrame(
        frame: Frame,
        block: () -> Unit,
    ) {
        frames += frame
        block()
        frames.removeLast()
    }

    inline fun conditionally(block: () -> Unit) {
        frames.last().branches++
        block()
        frames.last().branches--
    }

    inline fun inLoop(block: () -> Unit) {
        frames.last().loops++
        block()
        frames.last().loops--
    }

    inline fun inTry(
        tryResult: () -> Unit,
        handlersAndFinally: () -> Unit,
    ) {
        val frame = frames.last()
        frame.tries++
        frame.tryBodies++
        tryResult()
        frame.tryBodies--
        handlersAndFinally()
        frame.tries--
    }

    /** Whatever a function runs after a `return`, it does not run every time. */
    fun markReturned(target: IrReturnTargetSymbol) {
        frames.lastOrNull { it.function?.symbol == target }?.returned = true
    }

    fun markSuspended() {
        enclosingFunction().suspended = true
    }

    fun recordDelegatedProperty(declaration: IrLocalDelegatedProperty) {
        delegatedProperties[declaration.getter.symbol] = declaration
        declaration.setter?.let { delegatedProperties[it.symbol] = declaration }
    }

    /**
     * Decides how each lambda argument of [call] runs before the arguments are visited. When a stdlib scope function
     * (`let`, `apply`, etc.) aliases its subject onto the lambda's first parameter, passes `(subject, alias)` to
     * [onScopeAlias].
     */
    fun classifyLambdas(
        call: IrCall,
        callee: IrSimpleFunction,
        onScopeAlias: (IrExpression, IrValueSymbol) -> Unit,
    ) {
        call.arguments.forEachIndexed { index, argument ->
            val lambda = argument?.unwrapCasts() as? IrFunctionExpression ?: return@forEachIndexed
            val parameter = callee.parameters.getOrNull(index) ?: return@forEachIndexed
            val scope = scopeOf(call, callee, parameter, lambda.function)
            lambdaScopes[lambda] = scope
            val subject = call.arguments[0].takeIf { scope == Scope.BLOCK && index > 0 && callee.fqNameWhenAvailable in SCOPE_FUNCTIONS }
                ?: return@forEachIndexed
            val alias = lambda.function.parameters.firstOrNull()?.symbol ?: return@forEachIndexed
            onScopeAlias(subject, alias)
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
            // `div { }`, `Provider(value) { }`, `@ElementWrapper`: the body of an element being added to a builder.
            buildsElement && call.isOnBuilder(symbols) -> Scope.BLOCK
            // A render lambda given to anything else is somebody's component.
            lambda.isRenderLambda() -> Scope.COMPONENT
            // `Component.create { }`: the props of an element created on the spot.
            buildsElement -> Scope.BLOCK
            callee.fqNameWhenAvailable in RUN_ONCE_FUNCTIONS -> Scope.BLOCK
            callee.isInline && parameter.isInlined() -> Scope.IN_PLACE
            call.symbol in symbols.useMemoHooks -> Scope.MEMO
            call.symbol in symbols.effectHooks -> Scope.EFFECT
            else -> Scope.CALLBACK
        }
    }

    private fun IrFunction.buildsChildren(): Boolean =
        parameters.any { it.type.isSubtypeOfClass(symbols.childrenBuilderClass) }

    /** `ChildrenBuilder.(...) -> Unit`: the lambda of a component, as `FC` takes it. */
    private fun IrFunction.isRenderLambda(): Boolean =
        parameters.any { it.kind == IrParameterKind.ExtensionReceiver && it.type.classOrNull == symbols.childrenBuilderClass }

    /** Where the code being visited runs, found from the innermost frame outwards. */
    fun site(): Site {
        val site = Site()
        for (frame in frames.asReversed()) {
            site.conditional = site.conditional || frame.branches > 0
            site.repeated = site.repeated || frame.loops > 0
            site.guarded = site.guarded || frame.tries > 0
            site.tried = site.tried || frame.tryBodies > 0
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
    fun reactFunction(): Frame? =
        frames.lastOrNull { it.scope == Scope.COMPONENT || it.scope == Scope.HOOK }

    /** Whether the enclosing component or hook renders and has not opted out of React Compiler with `"use no memo"`. */
    fun isCheckedFunction(): Boolean =
        reactFunction()?.let { it.renders && !it.optsOut } == true

    /** The frame of the function whose body the code being visited belongs to, blocks aside. */
    fun enclosingFunction(): Frame =
        frames.last { !it.scope.isBlock }

    /** The functions whose locals the code being visited can read as values of its own component or hook. */
    fun localScope(): Set<IrDeclarationParent> =
        buildSet {
            for (frame in frames.asReversed()) {
                frame.function?.let(::add)
                if (!frame.scope.isBlock) break
            }
        }
}

/** How the code of a function runs, relative to the function around it. */
internal enum class Scope {
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

internal class Frame(
    val scope: Scope,
    /** The function whose body this is, the target of its `return`s. */
    val function: IrFunction?,
    /** Whether the function is known to run as part of a render, which the rules of render then apply to. */
    val renders: Boolean = false,
    val optsOut: Boolean = false,
) {
    var branches = 0
    var loops = 0
    var tries = 0
    var tryBodies = 0
    var returned = false

    /** Whether the function has called a suspend function: what follows runs later. */
    var suspended = false
}

internal class Site {
    /** The component or hook whose render runs the code. */
    var root: Frame? = null

    /** The function that keeps the code out of any render, when there is no [root]. */
    var barrier: Frame? = null
    var conditional = false
    var repeated = false

    /** Inside `try`, `catch` or `finally`. */
    var guarded = false

    /** Inside the body of a `try`. */
    var tried = false
    var afterReturn = false
    var inPlace = false
    var memo = false

    inline fun rendersIn(predicate: (Frame) -> Boolean): Boolean =
        root?.let(predicate) == true
}

/** Functions that call their lambda exactly once, where it is written. */
private val SCOPE_FUNCTIONS = setOf("apply", "also", "let", "run", "with", "takeIf", "takeUnless")
    .mapTo(mutableSetOf()) { FqName("kotlin.$it") }
private val RUN_ONCE_FUNCTIONS = SCOPE_FUNCTIONS + setOf(
    "kotlin.collections.buildList",
    "kotlin.collections.buildMap",
    "kotlin.collections.buildSet",
    "kotlin.text.buildString",
    "js.objects.jso",
    "js.objects.unsafeJso",
).map(::FqName)

/**
 * Whether an inline function inlines the lambda passed for this parameter, and so runs it where it is written. It
 * inlines a parameter of a function type only: a lambda passed for a type parameter, as to `useCallback`, is a value.
 */
private fun IrValueParameter.isInlined(): Boolean =
    !isNoinline && !isCrossinline && (type.isFunction() || type.isSuspendFunction()) && !type.isFunctionMarker()
