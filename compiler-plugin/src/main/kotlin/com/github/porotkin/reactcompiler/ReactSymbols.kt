package com.github.porotkin.reactcompiler

import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
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

private val KOTLIN_JS = FqName("kotlin.js")
private val REACT = FqName("react")
private val REACT_RAW = FqName("react.raw")
private val JSX_RUNTIME = FqName("react.jsx.runtime")
private val REACT_COMPILER = FqName("com.github.porotkin.reactcompiler")
private val FRAGMENT_PROPS = classId(REACT, "FragmentProps")

/** The kotlin-react, React runtime and Kotlin/JS declarations the lowerings reference. */
internal class ReactSymbols private constructor(
    private val finder: DeclarationFinder,
) {
    val js = function(KOTLIN_JS, "js")
    val jsx = function(JSX_RUNTIME, "jsx") { it.owner.parameters.size == 3 }

    /** React's `jsxs`, declared in the annotations artifact the Gradle plugin adds: kotlin-react's is internal. */
    val jsxs = function(REACT_COMPILER, "jsxs")
    val fragment = finder.findProperties(callable(REACT, "Fragment")).single().owner.getter!!.symbol
    val fragmentProps = classType(FRAGMENT_PROPS)
    val reactNode = classType(classId(REACT, "ReactNode")).makeNullable()
    val propsClass = classSymbol(classId(REACT, "Props"))
    val propsWithChildrenClass = classSymbol(classId(REACT, "PropsWithChildren"))
    val childrenBuilderClass = classSymbol(classId(REACT, "ChildrenBuilder"))
    val elementTypeClass = classSymbol(classId(REACT, "ElementType"))
    val fcClass = classSymbol(classId(REACT, "FC"))
    val charToString: IrSimpleFunctionSymbol = finder
        .findFunctions(CallableId(classId(FqName("kotlin"), "Char"), Name.identifier("toString")))
        .single { it.owner.parameters.size == 1 }
    val childrenSetter = finder
        .findProperties(CallableId(classId(REACT, "PropsWithChildren"), Name.identifier("children")))
        .single().owner.setter!!.symbol
    val eagerInitialization: IrConstructorSymbol = finder
        .findConstructors(classId(KOTLIN_JS, "EagerInitialization"))
        .single()

    /** `FC { props -> }` overloads, whose single lambda argument is a component body. */
    val componentFactories: Set<IrSimpleFunctionSymbol> =
        functions(REACT, "FC") { it.owner.parameters.size == 1 }.toSet()

    /** kotlin-react `(vararg dependencies, callback)` hooks mapped to their `(callback, dependencies)` raw form. */
    val rawHooks: Map<IrSimpleFunctionSymbol, IrSimpleFunctionSymbol> = listOf(
        functions(REACT, "useMemo") to function(REACT_RAW, "useMemoRaw"),
        functions(REACT, "useCallback") to function(REACT_RAW, "useCallbackRaw") {
            it.owner.typeParameters.single().superTypes.any { type -> type.classFqName == FqName("kotlin.Function") }
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
        error("React Compiler IR plugin: missing $name")

    companion object {
        /** Returns `null` when kotlin-react is not on the classpath, i.e. there is nothing to lower. */
        fun resolve(finder: DeclarationFinder): ReactSymbols? =
            if (finder.findClass(FRAGMENT_PROPS) == null) null else ReactSymbols(finder)
    }
}

private fun callable(
    packageName: FqName,
    name: String,
) = CallableId(packageName, Name.identifier(name))

private fun classId(
    packageName: FqName,
    name: String,
) = ClassId(packageName, Name.identifier(name))
