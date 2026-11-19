package com.github.porotkin.reactcompiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.buildVariable
import org.jetbrains.kotlin.ir.builders.irAnnotation
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
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

/** React's convention: a component is named with a capital letter. */
internal fun Name.isComponentName(): Boolean =
    !isSpecial && asString().first().isUpperCase()

private val STAMPED = FqName("com.github.porotkin.reactcompiler.Stamped")

/**
 * Records in `.klib` metadata that the plugin checked [function]. A compilation that gets the function from metadata
 * (another module, or an incremental build of this one) sees no body to check, so it trusts [isStamped] instead.
 * The stamp is the `annotations` artifact's internal `@Stamped`, which source code outside it cannot apply.
 */
internal fun IrPluginContext.stamp(
    function: IrSimpleFunction,
    symbols: ReactSymbols,
) {
    if (function.isStamped()) return
    metadataDeclarationRegistrar.addMetadataVisibleAnnotationsToElement(
        function,
        listOf(DeclarationIrBuilder(this, function.symbol).irAnnotation(symbols.stamped)),
    )
}

/** Whether the plugin checked this function where it was compiled, see [stamp]. */
internal fun IrSimpleFunction.isStamped(): Boolean =
    hasAnnotation(STAMPED)

/** Marks [function] `@JsNoLifting`, so Kotlin/JS emits it where it is written even when it captures nothing. */
internal fun IrPluginContext.noLifting(
    function: IrSimpleFunction,
    symbols: ReactSymbols,
) {
    if (function.annotations.any { it.symbol == symbols.jsNoLifting }) return
    function.annotations += DeclarationIrBuilder(this, function.symbol).irAnnotation(symbols.jsNoLifting)
}

/** Whether this calls a kotlin-react hook the hook lowerings left as is, e.g. one spreading its dependencies. */
internal fun IrElement.callsUnloweredHook(symbols: ReactSymbols): Boolean =
    any { it is IrCall && (it.symbol in symbols.rawHooks || it.symbol in symbols.effectHooks) }

/** `Object()`, a new empty object, which the compiler does not parse as it does `js("({})")`. */
internal fun IrBuilderWithScope.irEmptyObject(symbols: ReactSymbols): IrCall =
    irCall(symbols.emptyObject)

/** `[]`, typed `dynamic` like the `js("([])")` it replaces. */
internal fun IrBuilderWithScope.irEmptyArray(symbols: ReactSymbols): IrExpression {
    val element = context.irBuiltIns.anyNType
    val array = irCall(symbols.arrayOf, context.irBuiltIns.arrayClass.typeWith(element)).apply {
        typeArguments[0] = element
        arguments[0] = irVararg(element, emptyList())
    }
    return irImplicitCast(array, symbols.dynamicType)
}

/** `undefined` */
internal fun IrBuilderWithScope.irUndefined(symbols: ReactSymbols): IrCall =
    irCall(symbols.undefined)

/** `js("<code>")`: the backend parses the code at every call, so only where nothing else prints it. */
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

/** `__krcUseMemo()`, which vite-plugin-react-kotlinjs turns into the directive: the compiler parses a `js()` call. */
internal fun IrBuilderWithScope.irUseMemo(symbols: ReactSymbols): IrCall =
    irCall(symbols.useMemo)

internal fun IrStatement.isUseMemo(symbols: ReactSymbols): Boolean =
    (unwrapCoercion() as? IrCall)?.symbol == symbols.useMemo

/** React Compiler's opt-out directive: it leaves a function that starts with it as it is. */
private const val USE_NO_MEMO = "use no memo"

/** Whether the body starts with `js("\"use no memo\"")`. */
internal fun IrFunction.optsOutOfMemo(symbols: ReactSymbols): Boolean {
    val first = (body as? IrBlockBody)?.statements?.firstOrNull()?.unwrapCoercion() as? IrCall ?: return false
    val directive = (first.arguments.firstOrNull() as? IrConst)?.value as? String ?: return false
    return first.symbol == symbols.js && directive.trim().trim('"', '\'') == USE_NO_MEMO
}

internal fun IrStatement.unwrapCoercion(): IrStatement =
    if (this is IrTypeOperatorCall && operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) argument else this

/** Whether this element or any descendant matches [predicate]; stops descending at the first match. */
internal fun IrElement.any(predicate: (IrElement) -> Boolean): Boolean =
    find(predicate) != null

/** The first of this element and its descendants, in pre-order, that matches [predicate]. */
internal fun IrElement.find(predicate: (IrElement) -> Boolean): IrElement? {
    var found: IrElement? = null
    acceptVoid(
        object : IrVisitorVoid() {
            override fun visitElement(element: IrElement) {
                if (found != null) return
                if (predicate(element)) found = element else element.acceptChildrenVoid(this)
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
