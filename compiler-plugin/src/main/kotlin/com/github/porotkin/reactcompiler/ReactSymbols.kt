package com.github.porotkin.reactcompiler

import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val KOTLIN = FqName("kotlin")
private val KOTLIN_JS = FqName("kotlin.js")
private val REACT = FqName("react")
private val REACT_INTERNAL = FqName("react.internal")
private val REACT_RAW = FqName("react.raw")
private val REACT_USE = FqName("react.use")
private val JSX_RUNTIME = FqName("react.jsx.runtime")
private val REACT_COMPILER = FqName("com.github.porotkin.reactcompiler")
private val MUTABLE = FqName("com.github.porotkin.reactcompiler.Mutable")
private val PROPS = classId(REACT, "Props")
private val PROPS_WITH_CHILDREN = classId(REACT, "PropsWithChildren")
private val FRAGMENT_PROPS = classId(REACT, "FragmentProps")
private val FC = classId(REACT, "FC")
private val REACT_MARKERS = listOf(PROPS, FC, FRAGMENT_PROPS)

/** React's own effect hook a kotlin-react one calls; `*Once` variants pass it an empty dependency array. */
internal class EffectTarget(
    val raw: IrSimpleFunctionSymbol,
    val runOnce: Boolean,
)

/** `StateInstance` and what `val (value, setValue) = state` reads: `component1()`, `component2()` and `setValue(value)`. */
internal class StateDelegate(
    val instance: IrClassSymbol,
    val component1: IrSimpleFunctionSymbol,
    val component2: IrSimpleFunctionSymbol,
    val setter: IrClassSymbol,
    /** `StateSetter.invoke(value: T)`, as opposed to `invoke(transform: (T) -> T)`. */
    val setValue: IrSimpleFunctionSymbol,
)

/** The kotlin-react, React runtime and Kotlin/JS declarations the lowerings reference. */
internal class ReactSymbols private constructor(
    private val finder: DeclarationFinder,
) {
    val js = function(KOTLIN_JS, "js")
    val dynamicType: IrType get() = js.owner.returnType

    /** `Object()`, from the annotations artifact. */
    val emptyObject = function(REACT_COMPILER, "emptyObject")

    /** The call vite-plugin-react-kotlinjs turns into `"use memo"`, from the annotations artifact. */
    val useMemo = function(REACT_COMPILER, "__krcUseMemo")
    val undefined = finder.findProperties(callable(KOTLIN_JS, "undefined")).singleOrNull()?.owner?.getter?.symbol
        ?: missing("$KOTLIN_JS.undefined")

    /** `arrayOf()`, printed as `[]` without elements. */
    val arrayOf = function(KOTLIN, "arrayOf")

    /** `run(block)`, as opposed to `T.run(block)`. */
    val run = function(KOTLIN, "run") { it.owner.parameters.size == 1 }
    val jsx = function(JSX_RUNTIME, "jsx") { it.owner.parameters.size == 3 }

    /** React's `jsxs`, declared in the annotations artifact the Gradle plugin adds: kotlin-react's is internal. */
    val jsxs = function(REACT_COMPILER, "jsxs")
    val fragment = finder.findProperties(callable(REACT, "Fragment")).singleOrNull()?.owner?.getter?.symbol
        ?: missing("$REACT.Fragment")
    val fragmentProps = classType(FRAGMENT_PROPS)
    val reactNodeClass = classSymbol(classId(REACT, "ReactNode"))
    val reactNode = reactNodeClass.owner.defaultType.makeNullable()
    val propsClass = classSymbol(PROPS)
    val propsWithChildrenClass = classSymbol(PROPS_WITH_CHILDREN)
    val childrenBuilderClass = classSymbol(classId(REACT, "ChildrenBuilder"))
    val elementTypeClass = classSymbol(classId(REACT, "ElementType"))
    val fcClass = classSymbol(FC)
    val charToString: IrSimpleFunctionSymbol = finder
        .findFunctions(CallableId(classId(KOTLIN, "Char"), Name.identifier("toString")))
        .singleOrNull { it.owner.parameters.size == 1 }
        ?: missing("kotlin.Char.toString")
    val function0Invoke: IrSimpleFunctionSymbol = finder
        .findFunctions(CallableId(classId(KOTLIN, "Function0"), Name.identifier("invoke")))
        .singleOrNull { it.owner.parameters.size == 1 }
        ?: missing("kotlin.Function0.invoke")
    val childrenSetter = finder
        .findProperties(CallableId(PROPS_WITH_CHILDREN, Name.identifier("children")))
        .singleOrNull()?.owner?.setter?.symbol
        ?: missing("${PROPS_WITH_CHILDREN.asFqNameString()}.children")
    val stamped: IrConstructorSymbol = finder
        .findConstructors(classId(REACT_COMPILER, "Stamped"))
        .singleOrNull()
        ?: missing("$REACT_COMPILER.Stamped")
    val jsNoLifting: IrConstructorSymbol = finder
        .findConstructors(classId(KOTLIN_JS, "JsNoLifting"))
        .singleOrNull()
        ?: missing("$KOTLIN_JS.JsNoLifting")

    /** `FC { props -> }` overloads, whose single lambda argument is a component body. */
    val componentFactories: Set<IrSimpleFunctionSymbol> =
        functions(REACT, "FC") { it.owner.parameters.size == 1 }.toSet()

    /** `react.useMemo` overloads. */
    val useMemoHooks: Set<IrSimpleFunctionSymbol> =
        functions(REACT, "useMemo").toSet()

    /** kotlin-react `(vararg dependencies, callback)` hooks mapped to their `(callback, dependencies)` raw form. */
    val rawHooks: Map<IrSimpleFunctionSymbol, IrSimpleFunctionSymbol> = listOf(
        useMemoHooks to function(REACT_RAW, "useMemoRaw"),
        functions(REACT, "useCallback") to function(REACT_RAW, "useCallbackRaw") {
            it.owner.typeParameters.singleOrNull()?.superTypes?.any { type -> type.classFqName == FqName("kotlin.Function") } == true
        },
    ).flatMap { (sources, target) -> sources.map { it to target } }.toMap()

    /** kotlin-react `useEffect` / `useEffectOnce`, and their layout and insertion kinds, mapped to React's own hook. */
    val effectHooks: Map<IrSimpleFunctionSymbol, EffectTarget> =
        listOf("useEffect", "useLayoutEffect", "useInsertionEffect").flatMap { name ->
            val raw = function(REACT_COMPILER, name)
            finder.findFunctions(callable(REACT, name)).map { it to EffectTarget(raw, runOnce = false) } +
                finder.findFunctions(callable(REACT, "${name}Once")).map { it to EffectTarget(raw, runOnce = true) }
        }.toMap()

    /** `react.internal.createCleanupCallback(block)` when present on the classpath (real kotlin-react). */
    val createCleanupCallback: IrSimpleFunctionSymbol? = finder
        .findFunctions(callable(REACT_INTERNAL, "createCleanupCallback"))
        .singleOrNull { it.owner.parameters.size == 1 && it.owner.typeParameters.isEmpty() }

    /** `useRef()`: its `current` holds the ref's value. Optional so lint degrades gracefully when absent. */
    val refObjectClass: IrClassSymbol? = finder.findClass(classId(REACT, "RefObject"))

    /** `by useRefValue()`, and `by useLatest()` of kotlin-react-use: delegates that read and write a ref's `current`. */
    val refDelegates: List<IrClassSymbol> =
        listOfNotNull(finder.findClass(classId(REACT, "RefValueInstance")), finder.findClass(classId(REACT_USE, "LatestInstance")))

    /** `mutableValue(value)` of the annotations artifact, typed by vite-plugin-react-kotlinjs as a value React does not own. */
    val mutableValue = function(REACT_COMPILER, "mutableValue")

    /** Classes whose instances React does not own without being marked [MUTABLE]: DOM event targets, refs, preact signals. */
    private val mutableClasses: List<IrClassSymbol> = listOfNotNull(
        finder.findClass(classId(FqName("web.events"), "EventTarget")),
        refObjectClass,
        finder.findClass(classId(FqName("preact.signals.core"), "Signal")),
    )

    /** Whether a value of [type] is one React does not own: a DOM event target, a ref, a preact signal or a `@Mutable` class. */
    fun isMutable(type: IrType): Boolean {
        val irClass = type.classOrNull?.owner ?: return false
        return mutableClasses.any { type.isSubtypeOfClass(it) } || irClass.isMarkedMutable()
    }

    private fun IrClass.isMarkedMutable(): Boolean =
        hasAnnotation(MUTABLE) || superTypes.any { it.classOrNull?.owner?.isMarkedMutable() == true }

    val stateInstanceClass: IrClassSymbol? = finder.findClass(classId(REACT, "StateInstance"))
    val stateSetterClass: IrClassSymbol? = finder.findClass(classId(REACT, "StateSetter"))

    /** `by useState()`, or `null` when kotlin-react declares none of what [StateDelegate] needs. */
    val stateDelegate: StateDelegate? = run {
        val instanceId = classId(REACT, "StateInstance")
        val setterId = classId(REACT, "StateSetter")
        StateDelegate(
            instance = stateInstanceClass ?: return@run null,
            component1 = member(instanceId, "component1") { it.owner.parameters.size == 1 } ?: return@run null,
            component2 = member(instanceId, "component2") { it.owner.parameters.size == 1 } ?: return@run null,
            setter = stateSetterClass ?: return@run null,
            setValue = member(setterId, "invoke") {
                it.owner.parameters.size == 2 && it.owner.parameters[1].type.classifierOrNull is IrTypeParameterSymbol
            } ?: return@run null,
        )
    }

    private fun member(
        owner: ClassId,
        name: String,
        filter: (IrSimpleFunctionSymbol) -> Boolean,
    ): IrSimpleFunctionSymbol? =
        finder.findFunctions(CallableId(owner, Name.identifier(name))).singleOrNull(filter)

    private fun classSymbol(id: ClassId): IrClassSymbol =
        finder.findClass(id) ?: missing(id.asFqNameString())

    private fun classType(id: ClassId): IrType =
        classSymbol(id).owner.defaultType

    private fun function(
        packageName: FqName,
        name: String,
        filter: (IrSimpleFunctionSymbol) -> Boolean = { true },
    ): IrSimpleFunctionSymbol =
        functions(packageName, name, filter).first()

    private fun functions(
        packageName: FqName,
        name: String,
        filter: (IrSimpleFunctionSymbol) -> Boolean = { true },
    ): List<IrSimpleFunctionSymbol> =
        finder.findFunctions(callable(packageName, name)).filter(filter)
            .ifEmpty { missing("$packageName.$name") }

    private fun missing(name: String): Nothing =
        throw MissingSymbolException(name)

    companion object {
        /**
         * Returns `null` when kotlin-react is not on the classpath, or reports a warning and returns `null` when a
         * declaration the plugin needs is missing from the kotlin-react or annotations version on the classpath.
         */
        fun resolve(
            finder: DeclarationFinder,
            context: IrPluginContext,
        ): ReactSymbols? {
            if (REACT_MARKERS.all { finder.findClass(it) == null }) return null
            return try {
                ReactSymbols(finder)
            } catch (e: MissingSymbolException) {
                context.reportWarning(
                    "Kotlin React Compiler skipped lowering: declaration '${e.symbol}' was not found on the classpath (incompatible kotlin-react version?).",
                )
                null
            }
        }
    }
}

private class MissingSymbolException(
    val symbol: String,
) : Exception(symbol) {
    override fun fillInStackTrace(): Throwable = this
}

private fun callable(
    packageName: FqName,
    name: String,
) = CallableId(packageName, Name.identifier(name))

private fun classId(
    packageName: FqName,
    name: String,
) = ClassId(packageName, Name.identifier(name))
