package com.github.porotkin.reactcompiler.lint

import com.github.porotkin.reactcompiler.ReactSymbols
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass

/**
 * Tells refs and state apart by the classes of kotlin-react and kotlin-react-use resolved in [ReactSymbols]. A class
 * missing from the classpath matches nothing: the linter keeps working with a kotlin-react that names things differently.
 */
internal class LintSymbols(
    private val symbols: ReactSymbols,
) {
    fun isRef(type: IrType): Boolean = type.isA(symbols.refObjectClass)

    /** `ref.current`, read or assigned. */
    fun isRefAccess(call: IrCall): Boolean =
        call.symbol.owner.correspondingPropertySymbol?.owner?.name?.asString() == "current" && call.receiver()?.type?.let(::isRef) == true

    fun isRefValue(type: IrType): Boolean = symbols.refDelegates.any { type.isA(it) }

    fun isState(type: IrType): Boolean = type.isA(symbols.stateInstanceClass)

    fun isStateSetter(type: IrType): Boolean = type.isA(symbols.stateSetterClass)
}

private fun IrType.isA(symbol: IrClassSymbol?): Boolean =
    symbol != null && isSubtypeOfClass(symbol)
