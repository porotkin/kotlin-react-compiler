package com.github.porotkin.reactcompiler.lint

import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall

/** The object a member or an extension is called on. */
internal fun IrCall.receiver(): IrExpression? {
    val parameters = symbol.owner.parameters
    val index = parameters.indexOfFirst { it.kind == IrParameterKind.DispatchReceiver }
        .takeIf { it >= 0 } ?: parameters.indexOfFirst { it.kind == IrParameterKind.ExtensionReceiver }
    return arguments.getOrNull(index)
}

/** This expression without the casts and conversions around it. */
internal fun IrExpression.unwrapCasts(): IrExpression =
    if (this is IrTypeOperatorCall) argument.unwrapCasts() else this
