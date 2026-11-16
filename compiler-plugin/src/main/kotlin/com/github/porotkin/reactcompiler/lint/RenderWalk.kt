package com.github.porotkin.reactcompiler.lint

import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.isHookName
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRichPropertyReference
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid

/**
 * Walks [file] once, maintaining [RenderScope] and driving the focused rule checkers ([HookUsageRules],
 * [ExhaustiveDeps], [ImmutabilityRules], [RefRules], [StateRules], and [RenderPurityRules]).
 *
 * A function that opts out of React Compiler with `"use no memo"` is still held to the Rules of Hooks and to its
 * dependencies, which React itself needs; the rest it is trusted with.
 */
internal class RenderWalk(
    file: IrFile,
    symbols: ReactSymbols,
    lint: LintSymbols,
    reporter: LintReporter,
    builtIns: IrBuiltIns,
) : IrVisitorVoid() {
    private val scope = RenderScope(symbols)
    private val hooks = HookUsageRules(file, symbols, scope, reporter)
    private val dependencies = ExhaustiveDeps(file, symbols, lint, reporter, scope.delegated)
    private val immutability = ImmutabilityRules(file, symbols, lint, reporter, builtIns, scope.delegated)
    private val refs = RefRules(file, lint, reporter, builtIns, scope.delegated)
    private val state = StateRules(file, lint, refs, reporter, scope.delegated)
    private val purity = RenderPurityRules(file, symbols, reporter, builtIns)

    fun check(file: IrFile) {
        file.acceptChildrenVoid(this)
    }

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitSimpleFunction(declaration: IrSimpleFunction) {
        val frame = scope.frameFor(declaration)
        scope.inFrame(frame) {
            immutability.recordParameters(frame)
            declaration.acceptChildrenVoid(this)
        }
    }

    override fun visitConstructor(declaration: IrConstructor) {
        scope.inFrame(Frame(Scope.OTHER, declaration)) { declaration.acceptChildrenVoid(this) }
    }

    override fun visitClass(declaration: IrClass) {
        scope.inFrame(Frame(Scope.OTHER, null)) { declaration.acceptChildrenVoid(this) }
    }

    override fun visitFunctionExpression(expression: IrFunctionExpression) {
        val frame = scope.frameFor(expression)
        scope.inFrame(frame) {
            immutability.recordParameters(frame)
            expression.function.acceptChildrenVoid(this)
        }
    }

    override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty) {
        scope.recordDelegatedProperty(declaration)
        // The accessors only forward to the delegate: what matters is where the local is read and assigned.
        declaration.delegate?.acceptVoid(this)
    }

    override fun visitVariable(declaration: IrVariable) {
        declaration.acceptChildrenVoid(this)
        dependencies.recordVariable(declaration)
        immutability.recordVariable(declaration)
        refs.recordVariable(declaration)
    }

    override fun visitWhen(expression: IrWhen) {
        expression.branches.forEachIndexed { index, branch ->
            val initialized = refs.checkInitializationCondition(branch.condition)
            // The first condition is the only part of a `when` that always runs.
            if (index == 0) branch.condition.acceptVoid(this) else scope.conditionally { branch.condition.acceptVoid(this) }
            refs.inInitialization(initialized) {
                scope.conditionally { branch.result.acceptVoid(this) }
            }
        }
    }

    override fun visitLoop(loop: IrLoop) {
        scope.inLoop { loop.acceptChildrenVoid(this) }
    }

    override fun visitTry(aTry: IrTry) {
        scope.inTry(
            tryResult = { aTry.tryResult.acceptVoid(this) },
            handlersAndFinally = {
                aTry.catches.forEach { it.acceptVoid(this) }
                aTry.finallyExpression?.acceptVoid(this)
            },
        )
    }

    override fun visitReturn(expression: IrReturn) {
        expression.acceptChildrenVoid(this)
        scope.markReturned(expression.returnTargetSymbol)
    }

    override fun visitCall(expression: IrCall) {
        val callee = expression.symbol.owner
        val isHook = callee.name.isHookName()
        scope.classifyLambdas(expression, callee, immutability::recordScopeAlias)
        hooks.checkCall(expression, callee)
        if (isHook) dependencies.check(expression, callee, scope.localScope())
        val site = scope.site()
        val isChecked = scope.isCheckedFunction()
        val isRender = isChecked && site.root != null
        if (isChecked) immutability.checkCall(expression, callee, isRender && !site.memo)
        if (isRender) {
            refs.checkCall(expression, callee)
            state.checkRenderCall(expression, site)
            if (!site.memo) purity.checkCall(expression, callee, site)
        }

        expression.acceptChildrenVoid(this)

        // What only holds once the arguments have run.
        if (isChecked) state.checkEffectCall(expression, scope.enclosingFunction())
        if (isRender && !site.memo) immutability.freezePassedValues(expression, callee, isHook)
        if (callee.isSuspend) scope.markSuspended()
    }

    override fun visitConstructorCall(expression: IrConstructorCall) {
        purity.checkConstructorCall(expression, scope.site())
        expression.acceptChildrenVoid(this)
    }

    override fun visitGetValue(expression: IrGetValue) {
        hooks.checkGetValue(expression)
    }

    override fun visitPropertyReference(expression: IrPropertyReference) {
        refs.checkPropertyReference(expression, expression.arguments.firstOrNull(), scope.site())
        expression.acceptChildrenVoid(this)
    }

    override fun visitRichPropertyReference(expression: IrRichPropertyReference) {
        refs.checkPropertyReference(expression, expression.boundValues.firstOrNull(), scope.site())
        // The accessors of the reference are generated: only the value it is bound to is code of the file.
        expression.boundValues.forEach { it.acceptVoid(this) }
    }

    override fun visitRichFunctionReference(expression: IrRichFunctionReference) {
        // The function a `::reference` invokes through is generated: only the values it is bound to are code of the file.
        if (expression.reflectionTargetSymbol == null) expression.acceptChildrenVoid(this) else expression.boundValues.forEach { it.acceptVoid(this) }
    }

    override fun visitSetField(expression: IrSetField) {
        purity.checkSetField(expression, scope.site())
        expression.acceptChildrenVoid(this)
    }

    override fun visitTypeOperator(expression: IrTypeOperatorCall) {
        hooks.checkTypeOperator(expression)
        expression.acceptChildrenVoid(this)
    }
}
