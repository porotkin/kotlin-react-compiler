package com.github.porotkin.reactcompiler

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

/** React Compiler's opt-in directive: in `annotation` mode it only compiles functions that start with it. */
private const val USE_MEMO = "\"use memo\""

/** `js("<code>")` */
internal fun IrBuilderWithScope.irJs(
    symbols: ReactSymbols,
    code: String,
): IrCall =
    irCall(symbols.js).apply { arguments[0] = irString(code) }

internal fun IrBuilderWithScope.irUseMemo(symbols: ReactSymbols): IrCall =
    irJs(symbols, USE_MEMO)

internal fun IrStatement.isUseMemo(symbols: ReactSymbols): Boolean {
    val call = unwrapCoercion() as? IrCall ?: return false
    return call.symbol == symbols.js && (call.arguments[0] as? IrConst)?.value == USE_MEMO
}

internal fun IrStatement.unwrapCoercion(): IrStatement =
    if (this is IrTypeOperatorCall && operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) argument else this

/** Whether this element or any descendant matches [predicate]; stops descending at the first match. */
internal fun IrElement.any(predicate: (IrElement) -> Boolean): Boolean {
    var found = false
    acceptVoid(
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (found) return
                if (predicate(element)) found = true else element.acceptChildrenVoid(this)
            }
        },
    )
    return found
}
