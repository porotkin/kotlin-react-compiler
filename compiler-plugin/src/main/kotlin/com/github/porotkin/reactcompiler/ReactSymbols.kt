package com.github.porotkin.reactcompiler

import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val KOTLIN = FqName("kotlin")
private val KOTLIN_JS = FqName("kotlin.js")
private val REACT = FqName("react")
private val REACT_RAW = FqName("react.raw")
private val JSX_RUNTIME = FqName("react.jsx.runtime")
private val REACT_COMPILER = FqName("com.github.porotkin.reactcompiler")
private val PROPS = classId(REACT, "Props")
private val PROPS_WITH_CHILDREN = classId(REACT, "PropsWithChildren")
private val FRAGMENT_PROPS = classId(REACT, "FragmentProps")
private val FC = classId(REACT, "FC")
private val REACT_MARKERS = listOf(PROPS, FC, FRAGMENT_PROPS)

/** The kotlin-react, React runtime and Kotlin/JS declarations the lowerings reference. */
internal class ReactSymbols private constructor(
    private val finder: DeclarationFinder,
) {
    val js = function(KOTLIN_JS, "js")

    /** `run(block)`, as opposed to `T.run(block)`. */
    val run = function(KOTLIN, "run") { it.owner.parameters.size == 1 }
    val jsx = function(JSX_RUNTIME, "jsx") { it.owner.parameters.size == 3 }

    /** React's `jsxs`, declared in the annotations artifact the Gradle plugin adds: kotlin-react's is internal. */
    val jsxs = function(REACT_COMPILER, "jsxs")
    val fragment = finder.findProperties(callable(REACT, "Fragment")).singleOrNull()?.owner?.getter?.symbol
        ?: missing("$REACT.Fragment")
    val fragmentProps = classType(FRAGMENT_PROPS)
    val reactNode = classType(classId(REACT, "ReactNode")).makeNullable()
    val propsClass = classSymbol(PROPS)
    val propsWithChildrenClass = classSymbol(PROPS_WITH_CHILDREN)
    val childrenBuilderClass = classSymbol(classId(REACT, "ChildrenBuilder"))
    val elementTypeClass = classSymbol(classId(REACT, "ElementType"))
    val fcClass = classSymbol(FC)
    val charToString: IrSimpleFunctionSymbol = finder
        .findFunctions(CallableId(classId(KOTLIN, "Char"), Name.identifier("toString")))
        .singleOrNull { it.owner.parameters.size == 1 }
        ?: missing("kotlin.Char.toString")
    val childrenSetter = finder
        .findProperties(CallableId(PROPS_WITH_CHILDREN, Name.identifier("children")))
        .singleOrNull()?.owner?.setter?.symbol
        ?: missing("${PROPS_WITH_CHILDREN.asFqNameString()}.children")
    val stamped: IrConstructorSymbol = finder
        .findConstructors(classId(REACT_COMPILER, "Stamped"))
        .singleOrNull()
        ?: missing("$REACT_COMPILER.Stamped")

    /** `FC { props -> }` overloads, whose single lambda argument is a component body. */
    val componentFactories: Set<IrSimpleFunctionSymbol> =
        functions(REACT, "FC") { it.owner.parameters.size == 1 }.toSet()

    /** kotlin-react `(vararg dependencies, callback)` hooks mapped to their `(callback, dependencies)` raw form. */
    val rawHooks: Map<IrSimpleFunctionSymbol, IrSimpleFunctionSymbol> = listOf(
        functions(REACT, "useMemo") to function(REACT_RAW, "useMemoRaw"),
        functions(REACT, "useCallback") to function(REACT_RAW, "useCallbackRaw") {
            it.owner.typeParameters.singleOrNull()?.superTypes?.any { type -> type.classFqName == FqName("kotlin.Function") } == true
        },
    ).flatMap { (sources, target) -> sources.map { it to target } }.toMap()

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
