package com.github.porotkin.reactcompiler.lint

import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall

/** This expression without the casts and conversions around it. */
internal fun IrExpression.unwrapCasts(): IrExpression =
    if (this is IrTypeOperatorCall) argument.unwrapCasts() else this
