package com.github.porotkin.reactcompiler.lint

import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isArray
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isPrimitiveArray
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.name.FqName

private val USE_HOOK = FqName("react.use")

private val COLLECTION_MUTATORS = setOf(
    "add", "addAll", "addFirst", "addLast", "clear", "fill", "getOrPut", "minusAssign", "plusAssign", "put", "putAll",
    "remove", "removeAll", "removeAt", "removeFirst", "removeFirstOrNull", "removeLast", "removeLastOrNull", "replaceAll",
    "retainAll", "reverse", "set", "shuffle", "sort", "sortBy", "sortByDescending", "sortDescending", "sortWith",
)
private val ARRAY_MUTATORS = setOf("fill", "reverse", "set", "shuffle", "sort", "sortBy", "sortByDescending", "sortDescending", "sortWith")

/** The object a member or an extension is called on. */
internal fun IrCall.receiver(): IrExpression? =
    arguments.getOrNull(receiverIndex())

/** The index of [receiver] among the arguments, or -1 for a call with none. */
internal fun IrCall.receiverIndex(): Int {
    val parameters = symbol.owner.parameters
    return parameters.indexOfFirst { it.kind == IrParameterKind.DispatchReceiver }
        .takeIf { it >= 0 } ?: parameters.indexOfFirst { it.kind == IrParameterKind.ExtensionReceiver }
}

/** This expression without the casts and conversions around it. */
internal fun IrExpression.unwrapCasts(): IrExpression =
    if (this is IrTypeOperatorCall) argument.unwrapCasts() else this

/** React 19's `react.use`, which may run conditionally and in callbacks. */
internal fun IrSimpleFunction.isUseHook(): Boolean =
    name.asString() == "use" && fqNameWhenAvailable == USE_HOOK

/** Whether [this] is a member or stdlib extension that mutates the collection or array of [type] it is called on. */
internal fun IrSimpleFunction.isMutatorOf(
    type: IrType,
    builtIns: IrBuiltIns,
): Boolean {
    val isMutable = type.isSubtypeOfClass(builtIns.mutableCollectionClass) || type.isSubtypeOfClass(builtIns.mutableMapClass)
    return name.asString() in (if (isMutable) COLLECTION_MUTATORS else if (type.isArray() || type.isPrimitiveArray()) ARRAY_MUTATORS else return false)
}
