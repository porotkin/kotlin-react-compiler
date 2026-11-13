package com.github.porotkin.reactcompiler.lint

import com.github.porotkin.reactcompiler.isHookName
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrRichFunctionReference
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.ir.util.isFunctionTypeOrSubtype
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.name.Name

/**
 * [LintRule.REACT_HOOKS_AS_VALUES]: a hook is a function React can tell apart by where it is declared. It is never
 *
 * - referenced as a value (`::useValue`),
 * - kept in a property, a parameter or a variable (a function-typed one named like a hook),
 * - a member or a local function, whose call depends on an object or on a render.
 *
 * Each of these lets the hook a component calls change from one render to the next, which React does not allow and
 * React Compiler does not compile.
 */
internal class HookDeclarations(
    private val file: IrFile,
    private val reporter: LintReporter,
) : IrVisitorVoid() {
    fun check() {
        file.acceptChildrenVoid(this)
    }

    override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
    }

    override fun visitSimpleFunction(declaration: IrSimpleFunction) {
        // An override has no choice of name or place, and a member of an external class is somebody else's API.
        val isOwnDeclaration = !declaration.isFakeOverride && declaration.overriddenSymbols.isEmpty() && !declaration.isEffectivelyExternal()
        if (declaration.name.isHookName() && isOwnDeclaration) {
            val place = when {
                declaration.parentClassOrNull != null -> "a member of a class"
                declaration.parent is IrFunction -> "a local function"
                else -> null
            }
            if (place != null) {
                report(declaration, "Hook `${declaration.name}` is $place: which function a call runs is then not fixed. Declare hooks as top-level functions; if this is not a hook, rename it.")
            }
        }
        declaration.acceptChildrenVoid(this)
    }

    override fun visitProperty(declaration: IrProperty) {
        val type = declaration.getter?.returnType ?: declaration.backingField?.type
        val isOwnDeclaration = !declaration.isFakeOverride && declaration.overriddenSymbols.isEmpty()
        if (isOwnDeclaration && type != null) checkValue(declaration, declaration.name, type)
        declaration.acceptChildrenVoid(this)
    }

    override fun visitValueParameter(declaration: IrValueParameter) {
        // A `val` parameter of a constructor is reported as the property it declares.
        val declaresProperty = (declaration.parent as? IrConstructor)?.parentClassOrNull?.hasProperty(declaration.name) == true
        if (declaration.kind == IrParameterKind.Regular && !declaresProperty) checkValue(declaration, declaration.name, declaration.type)
        declaration.acceptChildrenVoid(this)
    }

    override fun visitVariable(declaration: IrVariable) {
        if (declaration.origin == IrDeclarationOrigin.DEFINED) checkValue(declaration, declaration.name, declaration.type)
        declaration.acceptChildrenVoid(this)
    }

    override fun visitLocalDelegatedProperty(declaration: IrLocalDelegatedProperty) {
        checkValue(declaration, declaration.name, declaration.type)
        declaration.delegate?.initializer?.acceptChildrenVoid(this)
    }

    private fun checkValue(
        declaration: IrElement,
        name: Name,
        type: IrType,
    ) {
        if (name.isHookName() && type.isFunctionTypeOrSubtype()) {
            report(declaration, "`$name` keeps a hook in a value: the hook a component calls could then change between renders. Call the hook directly, and pass its result or its arguments instead.")
        }
    }

    override fun visitFunctionReference(expression: IrFunctionReference) {
        checkReference(expression, expression.symbol)
        expression.acceptChildrenVoid(this)
    }

    override fun visitRichFunctionReference(expression: IrRichFunctionReference) {
        val target = expression.reflectionTargetSymbol
        // The function a `::reference` invokes through is generated: only the values it is bound to are code of the file.
        if (target == null) return expression.acceptChildrenVoid(this)
        checkReference(expression, target)
        expression.boundValues.forEach { it.acceptChildrenVoid(this) }
    }

    private fun checkReference(
        reference: IrElement,
        target: IrFunctionSymbol,
    ) {
        val name = target.owner.name
        if (name.isHookName()) {
            report(reference, "Hook `$name` is referenced as a value: the hook a component calls could then change between renders. Call the hook directly, and pass its result or its arguments instead.")
        }
    }

    private fun report(
        at: IrElement,
        message: String,
    ) {
        reporter.report(LintRule.REACT_HOOKS_AS_VALUES, file, at, message)
    }
}

private fun IrClass.hasProperty(name: Name): Boolean =
    declarations.any { it is IrProperty && it.name == name }
