package com.github.porotkin.reactcompiler.lint

import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val REACT = FqName("react")
private val REACT_USE = FqName("react.use")

/**
 * The classes of kotlin-react and kotlin-react-use the rules tell refs and state by. A class missing from the
 * classpath matches nothing: the linter keeps working with a kotlin-react that names things differently.
 */
internal class LintSymbols(
    finder: DeclarationFinder,
) {
    /** `useRef()`: its `current` is the ref's value. */
    private val refObject = finder.reactClass("RefObject")

    /** `by useRefValue()`, and `by useLatest()` of kotlin-react-use: a local delegated to a ref. */
    private val refValues = listOfNotNull(finder.reactClass("RefValueInstance"), finder.reactClass("LatestInstance", REACT_USE))

    /** `by useState()`: a local delegated to a state, whose assignment sets the state. */
    private val state = finder.reactClass("StateInstance")

    /** The second component of `useState()`. */
    private val stateSetter = finder.reactClass("StateSetter")

    fun isRef(type: IrType): Boolean = type.isA(refObject)

    /** `ref.current`, read or assigned. */
    fun isRefAccess(call: IrCall): Boolean =
        call.symbol.owner.correspondingPropertySymbol?.owner?.name?.asString() == "current" && call.receiver()?.type?.let(::isRef) == true

    fun isRefValue(type: IrType): Boolean = refValues.any { type.isA(it) }

    fun isState(type: IrType): Boolean = type.isA(state)

    fun isStateSetter(type: IrType): Boolean = type.isA(stateSetter)
}

private fun DeclarationFinder.reactClass(
    name: String,
    packageName: FqName = REACT,
): IrClassSymbol? =
    findClass(ClassId(packageName, Name.identifier(name)))

private fun IrType.isA(symbol: IrClassSymbol?): Boolean =
    symbol != null && isSubtypeOfClass(symbol)
