package com.github.porotkin.reactcompiler.hook

import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.irVal
import com.github.porotkin.reactcompiler.noLifting
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrVararg

/**
 * One kotlin-react hook call being rewritten into the shape React Compiler takes: built with [b] at the call's
 * position, with the values moved out of the call bound to locals that [wrap] places before it, and its callback a
 * lambda Kotlin/JS emits where it is written, which is the only callback React Compiler accepts.
 */
internal class HookCall(
    private val context: IrPluginContext,
    private val symbols: ReactSymbols,
    val b: DeclarationIrBuilder,
    private val parent: IrDeclarationParent,
) {
    private val locals = mutableListOf<IrVariable>()

    /**
     * [dependencies] with every element an identifier, the only dependency React Compiler accepts, or an empty array
     * for none. Other expressions are bound to locals, so Kotlin/JS's `EvaluationOrderTransformer` keeps the callback
     * inline instead of hoisting it next to them.
     */
    fun dependencies(dependencies: IrVararg?): IrExpression {
        dependencies ?: return b.irVararg(context.irBuiltIns.anyNType, emptyList())
        dependencies.elements.replaceAll { if (it is IrGetValue) it else bind(it as IrExpression, "dep") }
        return dependencies
    }

    /** [call], after the locals this rewrite bound. */
    fun wrap(call: IrExpression): IrExpression {
        if (locals.isEmpty()) return call
        return b.irBlock(resultType = call.type) {
            locals.forEach { +it }
            +call
        }
    }

    /** [callback], a lambda kept where it is written even when it captures nothing. */
    fun inlineCallback(callback: IrExpression): IrExpression {
        if (callback is IrFunctionExpression) context.noLifting(callback.function, symbols)
        return callback
    }

    private fun bind(
        value: IrExpression,
        name: String,
    ): IrExpression =
        b.irGet(irVal(parent, name, value).also(locals::add))
}

/** Whether this spreads an array into the dependencies, which React Compiler rejects: such calls stay as they are. */
internal fun IrVararg?.hasSpread(): Boolean =
    this?.elements?.any { it is IrSpreadElement } == true
