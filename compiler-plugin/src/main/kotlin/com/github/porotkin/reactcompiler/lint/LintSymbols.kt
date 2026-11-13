package com.github.porotkin.reactcompiler.lint

import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val REACT = FqName("react")

/**
 * The kotlin-react classes the rules tell refs and state by. A class the classpath does not have is `null`, and the
 * checks that need it find nothing: the linter keeps working with a kotlin-react that names things differently.
 */
internal class LintSymbols(
    finder: DeclarationFinder,
) {
    /** `useRef()`: its `current` is the ref's value. */
    private val refObject = finder.reactClass("RefObject")

    /** `by useRefValue()`: a local delegated to a ref. */
    private val refValue = finder.reactClass("RefValueInstance")

    /** `by useState()`: a local delegated to a state, whose assignment sets the state. */
    private val state = finder.reactClass("StateInstance")

    /** The second component of `useState()`. */
    private val stateSetter = finder.reactClass("StateSetter")

    fun isRef(type: IrType): Boolean = type.isA(refObject)

    fun isRefValue(type: IrType): Boolean = type.isA(refValue)

    fun isState(type: IrType): Boolean = type.isA(state)

    fun isStateSetter(type: IrType): Boolean = type.isA(stateSetter)
}

private fun DeclarationFinder.reactClass(name: String): IrClassSymbol? =
    findClass(ClassId(REACT, Name.identifier(name)))

private fun IrType.isA(symbol: IrClassSymbol?): Boolean =
    symbol != null && isSubtypeOfClass(symbol)
