package com.github.porotkin.reactcompiler.hook

import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.irVal
import com.github.porotkin.reactcompiler.isHookName
import com.github.porotkin.reactcompiler.noLifting
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlock
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrGetObjectValue
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.isFunction
import org.jetbrains.kotlin.ir.util.isKFunction
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

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

    /** [callback] as an inline lambda: a lambda as it is, a callable reference expanded when [expand] allows. */
    fun inlineCallback(
        callback: IrExpression,
        discardsReflection: Boolean,
    ): IrExpression {
        if (callback is IrFunctionExpression) {
            context.noLifting(callback.function, symbols)
            return callback
        }
        return callback.asCallableRef()?.let { expand(it, discardsReflection) } ?: callback
    }

    /**
     * [ref] as a lambda calling its target with its bound values, each a [stable] expression copied into the lambda or a
     * local bound before the call. A reference to a hook, to a `suspend` function, or typed as a `KFunction` the callee
     * may read the name of (unless it [discardsReflection]) stays a reference.
     */
    fun expand(
        ref: CallableRef,
        discardsReflection: Boolean,
    ): IrExpression {
        val type = ref.expression.type
        if (ref.isSuspend || !(type.isFunction() || discardsReflection && type.isKFunction()) || ref.target.name.isHookName()) {
            return ref.expression
        }
        val function = ref.adapter ?: adapterOf(ref.reference)
        val bound = function.parameters.zip(ref.reference.arguments)
            .mapNotNull { (parameter, value) -> value?.let { parameter.symbol to stable(it, "bound") } }
            .toMap()
        function.parameters = function.parameters.filter { it.symbol !in bound }.onEach { it.kind = IrParameterKind.Regular }
        function.body?.transformChildrenVoid(
            object : IrElementTransformerVoid() {
                override fun visitGetValue(expression: IrGetValue): IrExpression =
                    bound[expression.symbol]?.deepCopyWithSymbols() ?: expression
            },
        )
        val lambda = lambda(function, ref.expression)
        return if (lambda.type == type) lambda else b.irImplicitCast(lambda, type)
    }

    /** An empty function at the position of [source], to fill and pass to [lambda]. */
    fun newFunction(
        source: IrElement,
        returnType: IrType,
    ): IrSimpleFunction =
        context.irFactory.buildFun {
            startOffset = source.startOffset
            endOffset = source.endOffset
            name = SpecialNames.ANONYMOUS
            this.returnType = returnType
        }

    /** [function] as a lambda at the position of [source], kept inline by `@JsNoLifting` even when it captures nothing. */
    fun lambda(
        function: IrSimpleFunction,
        source: IrElement,
    ): IrFunctionExpression {
        function.origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
        function.visibility = DescriptorVisibilities.LOCAL
        function.name = SpecialNames.ANONYMOUS
        context.noLifting(function, symbols)
        function.patchDeclarationParents(parent)
        val type = context.irBuiltIns.functionN(function.parameters.size)
            .typeWith(function.parameters.map { it.type } + function.returnType)
        return IrFunctionExpressionImpl(source.startOffset, source.endOffset, type, function, IrStatementOrigin.LAMBDA)
    }

    /** What Kotlin adapts a reference through, for `::f` itself: a function passing its parameters to the target. */
    private fun adapterOf(reference: IrFunctionReference): IrSimpleFunction {
        val target = reference.symbol.owner
        val types = (reference.type as IrSimpleType).arguments.map { it.typeOrNull ?: context.irBuiltIns.anyNType }
        val unboundTypes = types.dropLast(1).iterator()
        val returnType = types.last()
        return newFunction(reference, returnType).apply {
            for (index in target.parameters.indices) {
                addValueParameter {
                    name = Name.identifier("p$index")
                    type = reference.arguments[index]?.type ?: unboundTypes.next()
                }
            }
            val fb = DeclarationIrBuilder(context, symbol, startOffset, endOffset)
            val call: IrFunctionAccessExpression = when (target) {
                is IrSimpleFunction -> fb.irCall(target.symbol)
                is IrConstructor -> fb.irCall(target.symbol)
            }
            reference.typeArguments.forEachIndexed { index, type -> call.typeArguments[index] = type }
            parameters.forEachIndexed { index, parameter -> call.arguments[index] = fb.irGet(parameter) }
            body = fb.irBlockBody {
                if (returnType.isUnit() && !call.type.isUnit()) {
                    +call
                    +irReturn(irUnit())
                } else {
                    +irReturn(if (call.type == returnType) call else irImplicitCast(call, returnType))
                }
            }
        }
    }

    /** [value] itself when [stable][isStable], otherwise a local bound to it, so it is still evaluated before the call. */
    fun stable(
        value: IrExpression,
        name: String,
    ): IrExpression =
        if (value.isStable()) value else bind(value, name)

    private fun bind(
        value: IrExpression,
        name: String,
    ): IrExpression =
        b.irGet(irVal(parent, name, value).also(locals::add))
}

/**
 * A callable reference in a shape Kotlin/JS 2.4 hands a plugin: `::f` / `x::f` as [reference] itself, or a reference
 * Kotlin adapted to the expected type (default arguments, `vararg`, a `Unit` result) through [adapter], a local
 * function calling the target, with [reference] pointing at it. Either way, [reference] binds its non-null arguments to
 * the parameters at their positions.
 */
internal class CallableRef(
    val expression: IrExpression,
    val reference: IrFunctionReference,
    val adapter: IrSimpleFunction?,
) {
    /** The function the reference names. */
    val target get() = (reference.reflectionTarget ?: reference.symbol).owner

    val isSuspend get() = adapter?.isSuspend ?: (reference.symbol.owner as? IrSimpleFunction)?.isSuspend == true

    val bound get() = reference.arguments.filterNotNull()
}

/** This as a [CallableRef]; `null` for other shapes, rich references included, which stay as they are. */
internal fun IrExpression.asCallableRef(): CallableRef? =
    when {
        this is IrFunctionReference -> CallableRef(this, this, adapter = null)
        this is IrBlock && origin == IrStatementOrigin.ADAPTED_FUNCTION_REFERENCE && statements.size == 2 -> {
            val adapter = statements[0] as? IrSimpleFunction
            val reference = statements[1] as? IrFunctionReference
            if (adapter != null && reference != null) CallableRef(this, reference, adapter) else null
        }
        else -> null
    }

/** Whether this spreads an array into the dependencies, which React Compiler rejects: such calls stay as they are. */
internal fun IrVararg?.hasSpread(): Boolean =
    this?.elements?.any { it is IrSpreadElement } == true

/** A constant, an object or a `val`: reads the same wherever it is copied. */
internal fun IrExpression.isStable(): Boolean =
    when (this) {
        is IrConst, is IrGetObjectValue -> true
        is IrGetValue -> (symbol.owner as? IrVariable)?.isVar != true
        else -> false
    }
