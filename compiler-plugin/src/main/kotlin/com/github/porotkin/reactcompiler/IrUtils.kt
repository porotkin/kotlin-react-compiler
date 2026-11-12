package com.github.porotkin.reactcompiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.builders.irAnnotation
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** How React Compiler recognizes a hook: by name only. */
private val HOOK_NAME = Regex("^use[A-Z0-9]")

internal fun Name.isHookName(): Boolean =
    HOOK_NAME.containsMatchIn(asString())

private val EAGER_INITIALIZATION = FqName("kotlin.js.EagerInitialization")

/**
 * Records in `.klib` metadata that the plugin checked [function]. A compilation that gets the function from metadata
 * (another module, or an incremental build of this one) sees no body to check, so it trusts [isStamped] instead.
 * The stamp is `@kotlin.js.EagerInitialization`: it targets properties, so source code cannot put it on a function.
 */
internal fun IrPluginContext.stamp(
    function: IrSimpleFunction,
    symbols: ReactSymbols,
) {
    if (function.isStamped()) return
    metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement(
        function,
        listOf(DeclarationIrBuilder(this, function.symbol).irAnnotation(symbols.eagerInitialization)),
    )
}

/** Whether the plugin checked this function where it was compiled, see [stamp]. */
internal fun IrSimpleFunction.isStamped(): Boolean =
    hasAnnotation(EAGER_INITIALIZATION)

/** React Compiler's opt-in directive: in `annotation` mode it only compiles functions that start with it. */
private const val USE_MEMO = "\"use memo\""

/** `js("<code>")` */
internal fun IrBuilderWithScope.irJs(
    symbols: ReactSymbols,
    code: String,
): IrCall =
    irCall(symbols.js).apply { arguments[0] = irString(code) }

/** `val <name>: <type> = <value>`, a local of [parent] at the position of [value]. */
internal fun irVal(
    parent: IrDeclarationParent,
    name: String,
    value: IrExpression,
    type: IrType = value.type,
): IrVariable =
    buildVariable(parent, value.startOffset, value.endOffset, IrDeclarationOrigin.DEFINED, Name.identifier(name), type)
        .apply { initializer = value }

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

/** Number of elements in this subtree, itself included, that match [predicate]. */
internal fun IrElement.count(predicate: (IrElement) -> Boolean): Int {
    var count = 0
    acceptVoid(
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (predicate(element)) count++
                element.acceptChildrenVoid(this)
            }
        },
    )
    return count
}
