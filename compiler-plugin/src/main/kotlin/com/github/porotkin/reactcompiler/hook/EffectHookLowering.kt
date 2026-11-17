package com.github.porotkin.reactcompiler.hook

import com.github.porotkin.reactcompiler.ReactLowering
import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.any
import com.github.porotkin.reactcompiler.irJs
import com.github.porotkin.reactcompiler.noLifting
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Rewrites kotlin-react `useEffect` / `useLayoutEffect` / `useInsertionEffect` (and `*Once`) calls to React's own hook
 * with an inline callback, the shape React Compiler takes. A lambda that neither suspends nor reads its
 * `CoroutineScope` [runs directly][runDirectly]; any other effect [runs as a coroutine][runAsCoroutine]. Calls
 * spreading a vararg into the dependencies are left as is.
 */
internal class EffectHookLowering(
    context: IrPluginContext,
    symbols: ReactSymbols,
) : ReactLowering(context, symbols) {
    private val anyNType = context.irBuiltIns.anyNType

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)
        val target = symbols.effectHooks[expression.symbol] ?: return expression
        val dependencies = if (expression.arguments.size == 2) expression.arguments[0] as IrVararg? else null
        if (dependencies.hasSpread()) return expression
        val effect = expression.arguments.last()!!
        val direct = (effect as? IrFunctionExpression)?.takeIf { it.function.isSynchronousEffect() }
        val createCleanupCallback = symbols.createCleanupCallback
        if (direct == null && createCleanupCallback == null) return expression
        val site = HookCall(context, symbols, builder(expression), currentDeclarationParent!!)
        val call = site.b.irCall(target.raw, context.irBuiltIns.unitType)
        // In kotlin-react's order, during render: the dependencies, then the effect.
        if (dependencies != null || target.runOnce) call.arguments[1] = site.dependencies(dependencies)
        call.arguments[0] = direct?.let(::runDirectly) ?: runAsCoroutine(effect, site, createCleanupCallback!!)
        return site.wrap(call)
    }

    /**
     * [effect] itself as `() -> Any?`, returning `undefined` where it returned `Unit`: Kotlin/JS's trailing
     * `return Unit_getInstance()` is an object, which React rejects as a cleanup.
     */
    private fun runDirectly(effect: IrFunctionExpression): IrExpression {
        val function = effect.function
        function.isSuspend = false
        function.parameters = emptyList()
        function.returnType = anyNType
        context.noLifting(function, symbols)
        function.body!!.transformChildrenVoid(
            object : IrElementTransformerVoid() {
                override fun visitReturn(expression: IrReturn): IrExpression {
                    expression.transformChildrenVoid(this)
                    if (expression.returnTargetSymbol != function.symbol) return expression
                    val b = builder(function.symbol, expression)
                    val value = expression.value
                    if (value is IrGetObjectValue && value.type.isUnit()) return b.irReturn(b.irJs(symbols, "undefined"))
                    return b.irBlock(resultType = context.irBuiltIns.nothingType) {
                        +value
                        +irReturn(irJs(symbols, "undefined"))
                    }
                }
            },
        )
        val body = function.body as IrBlockBody
        val b = DeclarationIrBuilder(context, function.symbol, body.endOffset, body.endOffset)
        body.statements += b.irReturn(b.irJs(symbols, "undefined"))
        effect.type = context.irBuiltIns.functionN(0).typeWith(anyNType)
        return effect
    }

    /**
     * `{ createCleanupCallback(effect)() }`, which starts [effect] as a coroutine when the effect runs, as kotlin-react
     * does, and returns its cancellation as the cleanup. A lambda's `$slambda`, which captures the values it reads, is
     * created then too; any other [effect] is evaluated during render, as an argument of kotlin-react's hook.
     */
    private fun runAsCoroutine(
        effect: IrExpression,
        site: HookCall,
        createCleanupCallback: IrSimpleFunctionSymbol,
    ): IrExpression {
        val value = if (effect is IrFunctionExpression) effect else site.stable(effect, "effect")
        val function = site.newFunction(effect, anyNType)
        val b = DeclarationIrBuilder(context, function.symbol, effect.startOffset, effect.endOffset)
        function.body = b.irBlockBody {
            +irReturn(
                irCall(symbols.function0Invoke, anyNType).apply {
                    arguments[0] = irCall(createCleanupCallback).apply { arguments[0] = value }
                },
            )
        }
        return site.lambda(function, effect)
    }
}

/**
 * Whether an effect lambda neither suspends nor uses its `CoroutineScope` receiver, so it can run directly as
 * `() -> Any?`. One with a `try` runs as a coroutine, as kotlin-react's does, which React Compiler sees only as a call:
 * React Compiler 1.0 does not compile a component or hook with a conditional, `?:` or `?.` value inside a `try` ("Todo").
 */
private fun IrSimpleFunction.isSynchronousEffect(): Boolean {
    val body = body as? IrBlockBody ?: return false
    val parameters = parameters.mapTo(hashSetOf()) { it.symbol }
    return !body.any {
        it is IrTry ||
            it is IrGetValue && it.symbol in parameters ||
            it is IrCall && it.symbol.owner.isSuspend ||
            it is IrFunctionReference && (it.symbol.owner as? IrSimpleFunction)?.isSuspend == true ||
            it is IrRichFunctionReference && it.invokeFunction.isSuspend ||
            it is IrFunctionExpression && it.function.isSuspend
    }
}
