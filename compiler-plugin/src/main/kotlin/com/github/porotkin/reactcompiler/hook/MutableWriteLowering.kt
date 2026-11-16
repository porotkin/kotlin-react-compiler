package com.github.porotkin.reactcompiler.hook

import com.github.porotkin.reactcompiler.ReactLowering
import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.isUseMemo
import com.github.porotkin.reactcompiler.lint.receiver
import com.github.porotkin.reactcompiler.lint.receiverIndex
import com.github.porotkin.reactcompiler.lint.unwrapCasts
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.util.isGetter
import org.jetbrains.kotlin.ir.util.isSetter
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Lets effects and callbacks write to values React does not own ([ReactSymbols.isMutable]: DOM nodes, refs, preact
 * signals, `@Mutable` classes).
 *
 * React Compiler treats props, state, hook arguments and hook results as frozen, and does not compile a component or
 * hook whose callback writes to one ("This value cannot be modified"). In a function it compiles, one starting with
 * `"use memo"`, a property write inside a lambda or a local function reads its receiver through `mutableValue()`
 * when the receiver, or the value it was read from through getters (`element.style.color = ...`), is mutable and
 * comes from the render scope: a parameter or a local of that function, read directly or through `val`s the lambdas
 * declare. A value a lambda creates, such as an element `useMemo` builds, is a local React Compiler lets it change,
 * and is left as it is.
 * vite-plugin-react-kotlinjs types `mutableValue()` as returning a value from outside React (`global`), so React
 * Compiler accepts the write in a callback and still rejects it when the callback is called during render. A write
 * outside any lambda runs during render and is left as it is.
 */
internal class MutableWriteLowering(
    context: IrPluginContext,
    symbols: ReactSymbols,
) : ReactLowering(context, symbols) {
    override fun visitFunctionNew(declaration: IrFunction): IrStatement {
        val body = declaration.body as? IrBlockBody
        if (body?.statements?.firstOrNull()?.isUseMemo(symbols) != true) return super.visitFunctionNew(declaration)
        body.transformChildrenVoid(CallbackWrites(declaration))
        return declaration
    }

    /** Rewrites the writes in the lambdas and local functions of [root], at any depth. */
    private inner class CallbackWrites(
        private val root: IrFunction,
    ) : IrElementTransformerVoid() {
        private val functions = ArrayDeque(listOf(root))

        override fun visitFunction(declaration: IrFunction): IrStatement {
            functions.addLast(declaration)
            try {
                return super.visitFunction(declaration)
            } finally {
                functions.removeLast()
            }
        }

        override fun visitCall(expression: IrCall): IrExpression {
            expression.transformChildrenVoid(this)
            if (functions.size > 1 && expression.symbol.owner.isSetter) readMutableReceiver(expression)
            return expression
        }

        /** Passes the receiver of [write], or the first one it was read from through getters that is mutable, through `mutableValue()`. */
        private fun readMutableReceiver(write: IrCall) {
            var holder = write
            while (true) {
                val index = holder.receiverIndex()
                val receiver = holder.arguments.getOrNull(index) ?: return
                if (symbols.isMutable(receiver.type)) {
                    if (!receiver.isFromRender()) return
                    val b = DeclarationIrBuilder(context, functions.last().symbol, receiver.startOffset, receiver.endOffset)
                    holder.arguments[index] = b.irCall(symbols.mutableValue, receiver.type).apply {
                        typeArguments[0] = receiver.type
                        arguments[0] = receiver
                    }
                    return
                }
                holder = (receiver.unwrapCasts() as? IrCall)?.takeIf { it.symbol.owner.isGetter } ?: return
            }
        }

        /** Whether this holds a value from the render scope of [root], read directly, through getters or through `val`s declared in lambdas. */
        private fun IrExpression.isFromRender(): Boolean =
            when (val value = unwrapCasts()) {
                is IrGetValue -> {
                    val declaration = value.symbol.owner
                    when {
                        !declaration.isInLambda() -> true
                        declaration is IrVariable && !declaration.isVar -> declaration.initializer?.isFromRender() == true
                        else -> false
                    }
                }
                is IrCall -> value.symbol.owner.isGetter && value.receiver()?.isFromRender() == true
                else -> false
            }

        /** Whether this is declared in a lambda or a local function of [root], as opposed to [root] itself or outside it. */
        private fun IrValueDeclaration.isInLambda(): Boolean {
            var parent: IrDeclarationParent? = parent
            while (parent != null && parent != root) {
                if (parent is IrFunction) return true
                parent = (parent as? IrDeclaration)?.parent
            }
            return false
        }
    }
}
