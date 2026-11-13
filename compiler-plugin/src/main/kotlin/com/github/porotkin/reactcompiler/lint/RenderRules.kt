package com.github.porotkin.reactcompiler.lint

import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.any
import com.github.porotkin.reactcompiler.isHookName
import com.github.porotkin.reactcompiler.optsOutOfMemo
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRichPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isArray
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isFunctionMarker
import org.jetbrains.kotlin.ir.util.isFunctionTypeOrSubtype
import org.jetbrains.kotlin.ir.util.isGetter
import org.jetbrains.kotlin.ir.util.isPrimitiveArray
import org.jetbrains.kotlin.ir.util.isSetter
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
 * - [LintRule.REACT_HOOKS_AS_VALUES]: nothing but a hook is named like one, where React Compiler reads the name;
 * - while a component or hook renders, it does not access a ref ([LintRule.REACT_REFS]), set state every time
 *   ([LintRule.REACT_SET_STATE_IN_RENDER]), assign or modify a global ([LintRule.REACT_GLOBALS]), create a component
 *   ([LintRule.REACT_STATIC_COMPONENTS]), or wrap a child element in `try` ([LintRule.REACT_ERROR_BOUNDARIES]);
 * - [LintRule.REACT_IMMUTABILITY]: props and state are never modified, and neither are, during render, hook
 *   arguments, hook results, and values already passed to a hook or to an element;
 * - [LintRule.REACT_SET_STATE_IN_EFFECT]: an effect does not set state as soon as it runs;
 * - [LintRule.REACT_USE_MEMO]: `useMemo` returns a value, and the value is used.
 *
 * The checks follow the code as it is written: a value is known to be props, state or a hook result only through
 * the `val`s it is assigned to, and a function called from a component is not looked into. React Compiler, which
 * tracks values further, finds more; what is reported here is reported with certainty, on the Kotlin line.
 *
 * A function that opts out of React Compiler with `"use no memo"` is still held to the Rules of Hooks and to its
 * dependencies, which React itself needs; the rest it is trusted with.
 */
internal class RenderRules(
    private val file: IrFile,
    private val symbols: ReactSymbols,
    private val lint: LintSymbols,
    private val reporter: LintReporter,
    private val builtIns: IrBuiltIns,
) : IrVisitorVoid() {
    private val frames = mutableListOf(Frame(Scope.OTHER, null))

    /** The scope of each lambda passed to the call being visited, decided by what the callee does with it. */
    private val lambdaScopes = mutableMapOf<IrFunctionExpression, Scope>()

    /** Props and state, and the `val`s read from them: snapshots that nothing may modify. */
    private val snapshots = mutableSetOf<IrValueSymbol>()

    /** Hook arguments, hook results, values passed to a hook or an element, and the `val`s read from them. */
    private val frozen = mutableSetOf<IrValueSymbol>()

    /** The accessors of `by` locals, which a read or an assignment of the local calls. */
    private val delegated = mutableMapOf<IrSimpleFunctionSymbol, IrLocalDelegatedProperty>()

    /** The hook each local was returned by, which tells the setter of `val (state, setState) = useState()`. */
    private val hookResults = mutableMapOf<IrValueSymbol, String>()

    /** The locals whose value is the same on every render, see [ExhaustiveDeps]. */
    private val stable = mutableSetOf<IrSymbol>()

    /** The locals computed from the value of a ref. */
    private val readFromRefs = mutableSetOf<IrValueSymbol>()

    /** Refs inside `if (ref.current == null) { ... }`, the one place render may initialize a ref. */
    private val initializedRefs = mutableListOf<IrValueSymbol>()
    private val initializationChecks = mutableSetOf<IrCall>()

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
        // A capitalized function is a component by its name only, so it is held to the rules of render only if it takes props.
        val renders = when (scope) {
            Scope.HOOK -> true
            Scope.COMPONENT -> declaration.buildsChildren() || declaration.parameters.any { it.type.isSubtypeOfClass(symbols.propsClass) }
            else -> false
        }
        inFrame(Frame(scope, declaration, renders, declaration.optsOutOfMemo(symbols))) {
            if (renders) declaration.arguments(withReceiver = scope == Scope.HOOK).forEach { (if (scope == Scope.HOOK) frozen else snapshots) += it.symbol }
            declaration.acceptChildrenVoid(this)
        }
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
        inFrame(Frame(scope, function, scope == Scope.COMPONENT, function.optsOutOfMemo(symbols))) {
            if (scope == Scope.COMPONENT) function.arguments(withReceiver = false).forEach { snapshots += it.symbol }
            function.acceptChildrenVoid(this)
        }
    }

    /** The parameters of a component or hook that its caller fills in, as opposed to the builder it adds children to. */
    private fun IrFunction.arguments(withReceiver: Boolean): List<IrValueParameter> =
        parameters.filter {
            val isArgument = it.kind == IrParameterKind.Regular || withReceiver && it.kind == IrParameterKind.ExtensionReceiver
            isArgument && !it.type.isSubtypeOfClass(symbols.childrenBuilderClass)
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
        when (value.immutability()) {
            Immutability.SNAPSHOT -> snapshots += declaration.symbol
            Immutability.FROZEN -> frozen += declaration.symbol
            Immutability.NONE -> {}
        }
        // As React's linter has it: a constant, a ref, an effect event, and the second of what these hooks return.
        val isSetter = callee == "component2" && (call.receiver() as? IrGetValue)?.symbol?.let(hookResults::get) in HOOKS_RETURNING_SETTERS
        if (value is IrConst || callee in HOOKS_RETURNING_STABLE_VALUES || isSetter) stable += declaration.symbol
        if (value.readsRef()) readFromRefs += declaration.symbol
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
            val initialized = branch.condition.refInitializationCheck()
            // The first condition is the only part of a `when` that always runs.
            if (index == 0) branch.condition.acceptVoid(this) else conditionally { branch.condition.acceptVoid(this) }
            if (initialized != null) initializedRefs += initialized
            conditionally { branch.result.acceptVoid(this) }
            if (initialized != null) initializedRefs.removeLast()
        }
    }

    /** The ref of `ref.current == null`, whose read is part of initializing the ref. */
    private fun IrExpression.refInitializationCheck(): IrValueSymbol? {
        val comparison = this as? IrCall ?: return null
        if (comparison.symbol != builtIns.eqeqSymbol && comparison.symbol != builtIns.eqeqeqSymbol) return null
        val read = comparison.arguments[0] as? IrCall ?: return null
        if ((comparison.arguments[1] as? IrConst)?.value != null || !read.isRefAccess()) return null
        val ref = (read.receiver() as? IrGetValue)?.symbol ?: return null
        initializationChecks += read
        return ref
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
        val frame = frames.last()
        frame.tries++
        frame.tryBodies++
        aTry.tryResult.acceptVoid(this)
        frame.tryBodies--
        aTry.catches.forEach { it.acceptVoid(this) }
        aTry.finallyExpression?.acceptVoid(this)
        frame.tries--
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
    private fun reactFunction(): Frame? =
        frames.lastOrNull { it.scope == Scope.COMPONENT || it.scope == Scope.HOOK }

    /** The frame of the function whose body the code being visited belongs to, blocks aside. */
    private fun enclosingFunction(): Frame =
        frames.last { !it.scope.isBlock }

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
        if (expression.isUseMemo() && expression.type.isUnit()) {
            report(LintRule.REACT_USE_MEMO, expression, "The callback of `useMemo` returns nothing. `useMemo` is for computing a value: run side effects in `useEffect` or in an event handler.")
        }
        val site = site()
        // Whether the code is held to the rules of render: it is in a component or hook that has not opted out of them.
        val isChecked = reactFunction()?.let { it.renders && !it.optsOut } == true
        val isRender = isChecked && site.root != null
        if (isChecked) checkMutation(expression, callee, isRender && !site.memo)
        if (isRender) {
            checkRefAccess(expression, callee)
            checkSetStateInRender(expression, site)
            if (!site.memo) {
                checkGlobalMutation(expression, callee)
                checkComponentCreation(expression, callee)
                checkElementInTry(expression, callee, site)
            }
        }

        expression.acceptChildrenVoid(this)

        // What only holds once the arguments have run.
        if (isChecked) checkSetStateInEffect(expression)
        if (isRender && !site.memo) freezePassedValues(expression, callee, isHook)
        if (callee.isSuspend) enclosingFunction().suspended = true
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
            val scope = scopeOf(call, callee, parameter, lambda.function)
            lambdaScopes[lambda] = scope
            // `value.apply { }`, `value.let { }`: the lambda's first parameter is the call's first argument.
            val subject = call.arguments[0].takeIf { scope == Scope.BLOCK && index > 0 && callee.fqNameWhenAvailable in SCOPE_FUNCTIONS }
            val alias = lambda.function.parameters.firstOrNull()?.symbol ?: return@forEachIndexed
            when (subject?.immutability()) {
                Immutability.SNAPSHOT -> snapshots += alias
                Immutability.FROZEN -> frozen += alias
                else -> {}
            }
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

    // Refs

    private fun checkRefAccess(
        call: IrCall,
        callee: IrSimpleFunction,
    ) {
        val local = delegated[call.symbol]
        val name = when {
            local != null -> local.name.takeIf { lint.isRefValue(local.delegate?.type ?: return) } ?: return
            !call.isRefAccess() || call in initializationChecks -> return
            (call.receiver() as? IrGetValue)?.symbol in initializedRefs -> return
            else -> "current"
        }
        val isWrite = if (local != null) call.symbol == local.setter?.symbol else callee.isSetter
        val access = if (isWrite) "written" else "read"
        val advice = "Access refs in effects and event handlers, or keep the value in state"
        report(LintRule.REACT_REFS, call, "Ref value `$name` is $access during render. $advice.")
    }

    /** `ref.current`, read or assigned. */
    private fun IrCall.isRefAccess(): Boolean {
        val property = symbol.owner.correspondingPropertySymbol?.owner ?: return false
        return property.name.asString() == "current" && receiver()?.type?.let(lint::isRef) == true
    }

    /** Whether the value of this expression comes from a ref. */
    private fun IrElement.readsRef(): Boolean =
        any {
            it is IrGetValue && it.symbol in readFromRefs ||
                it is IrCall && (it.isRefAccess() || delegated[it.symbol]?.delegate?.type?.let(lint::isRefValue) == true)
        }

    override fun visitPropertyReference(expression: IrPropertyReference) {
        checkRefReference(expression, expression.arguments.firstOrNull())
        expression.acceptChildrenVoid(this)
    }

    override fun visitRichPropertyReference(expression: IrRichPropertyReference) {
        checkRefReference(expression, expression.boundValues.firstOrNull())
        // The accessors of the reference are generated: only the value it is bound to is code of the file.
        expression.boundValues.forEach { it.acceptVoid(this) }
    }

    /** `ref::current` hands the ref itself to whatever takes the reference. */
    private fun checkRefReference(
        reference: IrExpression,
        bound: IrExpression?,
    ) {
        if (bound == null || !lint.isRef(bound.type)) return
        val site = site()
        if (!site.memo && site.rendersIn { it.renders && !it.optsOut }) {
            report(LintRule.REACT_REFS, reference, "A property of a ref is referenced during render, which passes the ref on. Use a lambda that reads it: `{ ref.current }`.")
        }
    }

    override fun visitRichFunctionReference(expression: IrRichFunctionReference) {
        // The function a `::reference` invokes through is generated: only the values it is bound to are code of the file.
        if (expression.reflectionTargetSymbol == null) expression.acceptChildrenVoid(this) else expression.boundValues.forEach { it.acceptVoid(this) }
    }

    // State

    /** `setState(value)`, or an assignment to a `by useState()` local. */
    private fun IrCall.setsState(): Boolean {
        val local = delegated[symbol]
        return if (local != null) {
            symbol == local.setter?.symbol && local.delegate?.type?.let(lint::isState) == true
        } else {
            symbol.owner.name.asString() == "invoke" && receiver()?.type?.let(lint::isStateSetter) == true
        }
    }

    private fun checkSetStateInRender(
        call: IrCall,
        site: Site,
    ) {
        // Setting state on some renders only is how state is derived from props.
        if (site.conditional || site.repeated || site.afterReturn || site.inPlace || !call.setsState()) return
        report(LintRule.REACT_SET_STATE_IN_RENDER, call, "State is set on every render, which renders again without end. Set state in an event handler, or compute the value during render.")
    }

    /**
     * An effect that sets state where it starts makes React render twice for what one render could compute. State
     * set later is not reported: in a callback the effect registers, after it suspends, or from the value of a ref,
     * which an effect is the place to read.
     */
    private fun checkSetStateInEffect(call: IrCall) {
        val effect = enclosingFunction()
        if (effect.scope != Scope.EFFECT || effect.suspended || !call.setsState() || call.readsRef()) return
        report(LintRule.REACT_SET_STATE_IN_EFFECT, call, "State is set as soon as the effect runs, which renders the component a second time. Compute the value during render, or set state where the change happens: in an event handler.")
    }

    // Immutability

    private fun checkMutation(
        call: IrCall,
        callee: IrSimpleFunction,
        isRender: Boolean,
    ) {
        val receiver = call.receiver() ?: return
        if (!callee.isSetter && !callee.isMutatorOf(receiver.type) || lint.isRef(receiver.type)) return
        val what = if (callee.isSetter) "`${callee.correspondingPropertySymbol!!.owner.name}` is assigned" else "`${callee.name}()` is called"
        val message = when (receiver.immutability()) {
            Immutability.SNAPSHOT -> "$what on props or state, which are snapshots of one render and must not be modified. Create a new value: pass it to the state setter, or to the child as a prop."
            Immutability.FROZEN -> "$what on a value React considers immutable. Hook arguments, values returned from hooks and values already passed to a hook or to an element must not be modified during render: copy the value, or change it before it is passed.".takeIf { isRender }
            Immutability.NONE -> null
        }
        if (message != null) report(LintRule.REACT_IMMUTABILITY, call, message)
    }

    /** A member or stdlib extension that changes the collection or array it is called on. */
    private fun IrSimpleFunction.isMutatorOf(type: IrType): Boolean {
        val isMutable = type.isSubtypeOfClass(builtIns.mutableCollectionClass) || type.isSubtypeOfClass(builtIns.mutableMapClass)
        return name.asString() in (if (isMutable) COLLECTION_MUTATORS else if (type.isArray() || type.isPrimitiveArray()) ARRAY_MUTATORS else return false)
    }

    /**
     * Whether this expression is one of the values that must not be modified, or is read from one: through
     * properties, destructuring, casts and `!!`, which all lead into the same object.
     */
    private fun IrExpression.immutability(): Immutability =
        when (this) {
            is IrGetValue -> if (symbol in snapshots) Immutability.SNAPSHOT else if (symbol in frozen) Immutability.FROZEN else Immutability.NONE
            is IrTypeOperatorCall -> argument.immutability()
            is IrContainerExpression -> (statements.lastOrNull() as? IrExpression)?.immutability() ?: Immutability.NONE
            is IrWhen -> branches.maxOf { it.result.immutability() }
            is IrCall -> {
                val callee = symbol.owner
                val local = delegated[symbol]
                val receiver = receiver()
                when {
                    // The value of `var state by useState()`.
                    local != null -> if (local.delegate?.type?.let(lint::isState) == true) Immutability.SNAPSHOT else Immutability.NONE
                    callee.name.isHookName() || callee.isUse() -> Immutability.FROZEN
                    symbol == builtIns.checkNotNullSymbol -> arguments[0]?.immutability() ?: Immutability.NONE
                    receiver == null -> Immutability.NONE
                    // The first of `val (state, setState) = useState()`.
                    callee.name.asString() == "component1" && lint.isState(receiver.type) -> Immutability.SNAPSHOT
                    callee.isGetter || callee.isOperator && COMPONENT_N.matches(callee.name.asString()) -> receiver.immutability()
                    else -> Immutability.NONE
                }
            }
            else -> Immutability.NONE
        }

    /**
     * "Values are immutable after being passed to JSX", and to a hook: a local given to either is frozen from here
     * on. To an element it is given as a prop, assigned in the element's body, or as an argument of a builder call.
     */
    private fun freezePassedValues(
        call: IrCall,
        callee: IrSimpleFunction,
        isHook: Boolean,
    ) {
        val isProp = callee.isSetter && call.receiver()?.type?.isSubtypeOfClass(symbols.propsClass) == true
        if (!isHook && !isProp && !call.isOnBuilder(callee)) return
        callee.parameters.forEachIndexed { index, parameter ->
            val argument = call.arguments[index].takeIf { parameter.kind == IrParameterKind.Regular } ?: return@forEachIndexed
            val values = if (argument is IrVararg) argument.elements.filterIsInstance<IrExpression>() else listOf(argument)
            for (value in values) {
                val local = (value.unwrapCasts() as? IrGetValue)?.symbol ?: continue
                if (local.owner is IrVariable && local !in snapshots) frozen += local
            }
        }
    }

    // Globals

    private fun checkGlobalMutation(
        call: IrCall,
        callee: IrSimpleFunction,
    ) {
        val receiver = call.receiver()
        if (callee.isSetter) {
            val property = callee.correspondingPropertySymbol!!.owner.name
            when {
                receiver?.isGlobal() == true -> report(LintRule.REACT_GLOBALS, call, globalMessage("`$property` is assigned on a value"))
                receiver == null && callee.correspondingPropertySymbol!!.owner.parent is IrPackageFragment -> report(LintRule.REACT_GLOBALS, call, globalMessage("`$property` is assigned, which is"))
            }
        } else if (receiver != null && callee.isMutatorOf(receiver.type) && receiver.isGlobal()) {
            report(LintRule.REACT_GLOBALS, call, globalMessage("`${callee.name}()` is called on a value"))
        }
    }

    /** An `object`, a top-level property, or a property read from one: what exists apart from any render. */
    private fun IrExpression.isGlobal(): Boolean =
        when (this) {
            is IrGetObjectValue -> true
            is IrTypeOperatorCall -> argument.isGlobal()
            is IrCall -> symbol.owner.isGetter && (receiver()?.isGlobal() ?: (symbol.owner.correspondingPropertySymbol!!.owner.parent is IrPackageFragment))
            else -> false
        }

    override fun visitSetField(expression: IrSetField) {
        val site = site()
        val isGlobal = expression.receiver.let { it == null || it is IrGetObjectValue }
        if (isGlobal && !site.memo && site.rendersIn { it.renders && !it.optsOut }) {
            report(LintRule.REACT_GLOBALS, expression, globalMessage("`${expression.symbol.owner.name}` is assigned, which is"))
        }
        expression.acceptChildrenVoid(this)
    }

    // Static components

    private fun checkComponentCreation(
        call: IrCall,
        callee: IrSimpleFunction,
    ) {
        val creates = call.symbol in symbols.componentFactories || callee.hasAnnotation(COMPONENT_WRAPPER) || callee.fqNameWhenAvailable in COMPONENT_FACTORIES
        if (creates) {
            report(LintRule.REACT_STATIC_COMPONENTS, call, "A component is created during render: every render makes a new one, and React resets its state. Declare the component at the top level of the file.")
        }
    }

    // Error boundaries

    private fun checkElementInTry(
        call: IrCall,
        callee: IrSimpleFunction,
        site: Site,
    ) {
        val createsElement = site.tried && callee.name.asString() in ELEMENT_FUNCTIONS && call.isOnBuilder(callee)
        if (createsElement) {
            report(LintRule.REACT_ERROR_BOUNDARIES, call, "An element is created inside `try`, but its component renders later: what it throws is not caught here. Catch the errors of child components with an error boundary.")
        }
    }

    // useMemo

    override fun visitTypeOperator(expression: IrTypeOperatorCall) {
        val discarded = expression.argument.takeIf { expression.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT } as? IrCall
        if (discarded != null && discarded.isUseMemo() && !discarded.type.isUnit()) {
            report(LintRule.REACT_USE_MEMO, discarded, "The value `useMemo` computes is not used. `useMemo` is for computing a value: run side effects in `useEffect` or in an event handler.")
        }
        expression.acceptChildrenVoid(this)
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

private class Site {
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

/** How firmly React holds a value, weakest first. */
private enum class Immutability {
    NONE,

    /** Not to be modified while rendering: it was given to React or came from it. */
    FROZEN,

    /** Never to be modified: props and state. */
    SNAPSHOT,
}

private const val EVERY_RENDER = "A component or hook must call the same hooks in the same order on every render"

private fun notAHookMessage(name: String): String =
    "`$name` is not a hook, but React Compiler takes every name that starts with `use` for one. Rename it."

private fun globalMessage(what: String): String =
    "$what declared outside the component or hook, during render. Rendering must not change what it did not create: move the change to an effect or an event handler."

/** The operators a destructuring declaration calls. */
private val COMPONENT_N = Regex("component\\d+")
private val COMPONENT_WRAPPER = FqName("com.github.porotkin.reactcompiler.ComponentWrapper")
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

private val COMPONENT_FACTORIES = setOf("react.memo", "react.forwardRef", "react.lazy").mapTo(mutableSetOf(), ::FqName)

/** What adds a child to a builder: `type { }` and `child(type, props)`. */
private val ELEMENT_FUNCTIONS = setOf("invoke", "child")

/** The hooks whose result never changes, and those that return a pair whose second part never does. */
private val HOOKS_RETURNING_STABLE_VALUES = setOf("useRef", "useEffectEvent")
private val HOOKS_RETURNING_SETTERS = setOf("useState", "useReducer", "useActionState", "useTransition")

private val COLLECTION_MUTATORS = setOf(
    "add", "addAll", "addFirst", "addLast", "clear", "fill", "getOrPut", "minusAssign", "plusAssign", "put", "putAll",
    "remove", "removeAll", "removeAt", "removeFirst", "removeFirstOrNull", "removeLast", "removeLastOrNull", "replaceAll",
    "retainAll", "reverse", "set", "shuffle", "sort", "sortBy", "sortByDescending", "sortDescending", "sortWith",
)
private val ARRAY_MUTATORS = setOf("fill", "reverse", "set", "shuffle", "sort", "sortBy", "sortByDescending", "sortDescending", "sortWith")

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
