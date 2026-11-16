package com.github.porotkin.reactcompiler.lint

import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.createsComponent
import com.github.porotkin.reactcompiler.createsElement
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrPackageFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isGetter
import org.jetbrains.kotlin.ir.util.isSetter
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.name.FqName

/**
 * Stateless rules about what pure rendering must not do: mutate globals ([LintRule.REACT_GLOBALS]), call impure
 * functions ([LintRule.REACT_PURITY]), create components ([LintRule.REACT_STATIC_COMPONENTS]), or wrap child elements
 * in `try` ([LintRule.REACT_ERROR_BOUNDARIES]).
 */
internal class RenderPurityRules(
    private val file: IrFile,
    private val symbols: ReactSymbols,
    private val reporter: LintReporter,
    private val builtIns: IrBuiltIns,
) {
    fun checkCall(
        call: IrCall,
        callee: IrSimpleFunction,
        site: Site,
    ) {
        checkGlobalMutation(call, callee)
        if (call.createsComponent(symbols)) {
            report(
                LintRule.REACT_STATIC_COMPONENTS,
                call,
                "A component is created during render: every render makes a new one, and React resets its state. Declare the component at the top level of the file.",
            )
        }
        if (site.tried && call.createsElement(symbols)) {
            report(
                LintRule.REACT_ERROR_BOUNDARIES,
                call,
                "An element is created inside `try`, but its component renders later: what it throws is not caught here. Catch the errors of child components with an error boundary.",
            )
        }
        if (callee.isImpure()) report(LintRule.REACT_PURITY, call, impureMessage("${callee.name}()"))
    }

    fun checkConstructorCall(
        expression: IrConstructorCall,
        site: Site,
    ) {
        val type = expression.symbol.owner.parentAsClass
        val isNow = type.fqNameWhenAvailable in DATE_CLASSES && expression.arguments.isEmpty()
        if (isNow && !site.memo && site.rendersIn { it.renders && !it.optsOut }) {
            report(LintRule.REACT_PURITY, expression, impureMessage("${type.name}()"))
        }
    }

    fun checkSetField(
        expression: IrSetField,
        site: Site,
    ) {
        val isGlobal = expression.receiver.let { it == null || it is IrGetObjectValue }
        if (isGlobal && !site.memo && site.rendersIn { it.renders && !it.optsOut }) {
            report(LintRule.REACT_GLOBALS, expression, globalMessage("`${expression.symbol.owner.name}` is assigned, which is"))
        }
    }

    private fun checkGlobalMutation(
        call: IrCall,
        callee: IrSimpleFunction,
    ) {
        val receiver = call.receiver()
        if (callee.isSetter) {
            val property = callee.correspondingPropertySymbol!!.owner.name
            when {
                receiver?.isGlobal() == true -> report(LintRule.REACT_GLOBALS, call, globalMessage("`$property` is assigned on a value"))
                receiver == null && callee.correspondingPropertySymbol!!.owner.parent is IrPackageFragment -> report(LintRule.REACT_GLOBALS, call, globalMessage("`$property` is assigned, which is"))
            }
        } else if (receiver != null && callee.isMutatorOf(receiver.type, builtIns) && receiver.isGlobal()) {
            report(LintRule.REACT_GLOBALS, call, globalMessage("`${callee.name}()` is called on a value"))
        }
    }

    /** An `object`, a top-level property, or a property read from one: what exists apart from any render. */
    private fun IrExpression.isGlobal(): Boolean =
        when (this) {
            is IrGetObjectValue -> true
            is IrTypeOperatorCall -> argument.isGlobal()
            is IrCall -> symbol.owner.isGetter && (receiver()?.isGlobal() ?: (symbol.owner.correspondingPropertySymbol!!.owner.parent is IrPackageFragment))
            else -> false
        }

    private fun IrSimpleFunction.isImpure(): Boolean {
        val owner = parentClassOrNull
        val name = name.asString()
        return when {
            owner == null -> fqNameWhenAvailable in IMPURE_FUNCTIONS
            owner.isA(RANDOM) -> true
            else -> IMPURE_MEMBERS.any { (type, member) -> member == name && owner.isA(type) }
        }
    }

    private fun report(
        rule: LintRule,
        at: IrElement,
        message: String,
    ) {
        reporter.report(rule, file, at, message)
    }
}

private val RANDOM = FqName("kotlin.random.Random")
private val DATE_CLASSES = setOf(FqName("kotlin.js.Date"), FqName("js.date.Date"))

private val IMPURE_FUNCTIONS = setOf(
    "kotlin.collections.random",
    "kotlin.collections.randomOrNull",
    "kotlin.collections.shuffled",
    "kotlin.ranges.random",
    "kotlin.ranges.randomOrNull",
).mapTo(mutableSetOf(), ::FqName)

/** Members that read the clock or a random source, by the class that declares them. */
private val IMPURE_MEMBERS = listOf(
    "kotlin.js.Date.Companion" to "now",
    "js.date.Date.Companion" to "now",
    "kotlin.time.Clock" to "now",
    "kotlinx.datetime.Clock" to "now",
    "kotlin.time.TimeSource" to "markNow",
    "kotlin.uuid.Uuid.Companion" to "random",
    "web.performance.Performance" to "now",
    "web.crypto.Crypto" to "randomUUID",
).map { (type, member) -> FqName(type) to member }

private fun globalMessage(what: String): String =
    "$what declared outside the component or hook, during render. Rendering must not change what it did not create: move the change to an effect or an event handler."

private fun impureMessage(call: String): String =
    "`$call` returns something else on every call, so the result of rendering changes with it. Call it in an effect or an event handler, or keep its result in state: `useState { ... }`."

private fun IrClass.isA(type: FqName): Boolean =
    fqNameWhenAvailable == type || superTypes.any { it.classOrNull?.owner?.isA(type) == true }
