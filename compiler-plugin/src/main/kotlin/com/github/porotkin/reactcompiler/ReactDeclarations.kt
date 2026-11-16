package com.github.porotkin.reactcompiler

import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isNothing
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.types.makeNotNull
import org.jetbrains.kotlin.ir.util.allOverridden
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass
import org.jetbrains.kotlin.ir.util.parentClassOrNull
import org.jetbrains.kotlin.name.FqName

private val COMPONENT_WRAPPER = FqName("com.github.porotkin.reactcompiler.ComponentWrapper")
private val ELEMENT_WRAPPER = FqName("com.github.porotkin.reactcompiler.ElementWrapper")
private val ELEMENT_BUILDER = FqName("react.ElementBuilder")
private val COMPONENT_FACTORIES = setOf("react.memo", "react.forwardRef", "react.lazy").mapTo(mutableSetOf(), ::FqName)

internal val SET_DEFAULT_KEY = FqName("react.setDefaultKey")

internal fun IrSimpleFunction.isComponentWrapper(): Boolean =
    hasAnnotation(COMPONENT_WRAPPER)

internal fun IrSimpleFunction.isElementWrapper(): Boolean =
    hasAnnotation(ELEMENT_WRAPPER)

/** A top-level `val Card: FC<...>` property, how kotlin-react components are declared and exported. */
internal fun IrDeclaration.isComponentProperty(symbols: ReactSymbols): Boolean =
    this is IrProperty &&
        name.isComponentName() &&
        getter?.parameters.isNullOrEmpty() &&
        (getter?.returnType ?: backingField?.type)?.isSubtypeOfClass(symbols.fcClass) == true

/**
 * The `ChildrenBuilder` extension receiver of a `ChildrenBuilder.(...) -> Unit` render lambda or helper, or `null`
 * when [this] has another signature.
 */
internal fun IrFunction.renderBuilderParameter(symbols: ReactSymbols): IrValueParameter? {
    if (!returnType.isUnit()) return null
    return parameters.singleOrNull {
        it.kind == IrParameterKind.ExtensionReceiver && it.type.classOrNull == symbols.childrenBuilderClass
    }
}

/** The `FC` factory overload matching [renderType]'s function arity, if any. */
internal fun ReactSymbols.componentFactoryFor(renderType: IrSimpleType): IrSimpleFunctionSymbol? =
    componentFactories.firstOrNull { it.owner.parameters[0].type.classOrNull == renderType.classOrNull }

/** `ChildrenBuilder.(props) -> Unit`, the lambda type of an `FC` factory. */
internal fun IrType.isRenderLambdaType(symbols: ReactSymbols): Boolean {
    if (this !is IrSimpleType || symbols.componentFactoryFor(this) == null) return false
    val receiver = (arguments.firstOrNull() as? IrTypeProjection)?.type
    val result = (arguments.lastOrNull() as? IrTypeProjection)?.type
    return receiver?.classOrNull == symbols.childrenBuilderClass && result?.isUnit() == true
}

/** The parameter of a `@ComponentWrapper` function that takes the render lambda, when its signature fits. */
internal fun IrSimpleFunction.componentWrapperRenderParameter(symbols: ReactSymbols): IrValueParameter? {
    if (!isComponentWrapper() || !returnType.isSubtypeOfClass(symbols.fcClass)) return null
    return parameters.singleOrNull { it.type.isRenderLambdaType(symbols) }
}

/** Whether [this] constructs and returns a component (`@ComponentWrapper`, or `: FC<...>`) rather than rendering. */
internal fun IrSimpleFunction.returnsComponent(symbols: ReactSymbols): Boolean =
    isComponentWrapper() || returnType.isSubtypeOfClass(symbols.fcClass)

/**
 * Whether [this] takes a `ChildrenBuilder` to add elements to: as a receiver or context parameter (including
 * `PropsWithChildren` subtypes), or as an explicit `ChildrenBuilder` parameter. Plain `PropsWithChildren` regular
 * parameters are props, not builders.
 */
internal fun IrFunction.buildsChildren(symbols: ReactSymbols): Boolean =
    parameters.any {
        it.type.classOrNull == symbols.childrenBuilderClass ||
            it.kind != IrParameterKind.Regular && it.type.isSubtypeOfClass(symbols.childrenBuilderClass)
    }

/** Whether [this] named function is a component or `ChildrenBuilder` helper, as opposed to a hook or factory. */
internal fun IrSimpleFunction.isComponentFunction(symbols: ReactSymbols): Boolean =
    !name.isHookName() && !returnsComponent(symbols) && (buildsChildren(symbols) || name.isComponentName())

/**
 * Whether a component function is known to run during render, rather than being capitalized by Kotlin factory
 * convention alone: it builds children, takes `Props`, or returns `ReactNode`.
 */
internal fun IrSimpleFunction.rendersComponent(symbols: ReactSymbols): Boolean =
    buildsChildren(symbols) ||
        parameters.any { it.type.isSubtypeOfClass(symbols.propsClass) } ||
        !returnType.isNothing() && returnType.makeNotNull().isSubtypeOfClass(symbols.reactNodeClass)

/** Whether [this] call is made on a `ChildrenBuilder`: as its member, its extension, or in its context. */
internal fun IrCall.isOnBuilder(symbols: ReactSymbols): Boolean =
    symbol.owner.parameters.withIndex().any { (index, parameter) ->
        parameter.kind != IrParameterKind.Regular && arguments[index]?.type?.isSubtypeOfClass(symbols.childrenBuilderClass) == true
    }

/** kotlin-react's `@ElementBuilder operator fun invoke(...)` on `ChildrenBuilder`. */
internal fun IrSimpleFunction.isElementBuilder(): Boolean =
    name.asString() == "invoke" && allOverridden(includeSelf = true).any { it.hasAnnotation(ELEMENT_BUILDER) }

/** A member declared on `ChildrenBuilder` itself (`unaryPlus`, `child`), or an override of one. */
internal fun IrSimpleFunction.isChildrenBuilderMember(
    name: String,
    symbols: ReactSymbols,
    arity: Int,
): Boolean =
    this.name.asString() == name &&
        parameters.size == arity &&
        allOverridden(includeSelf = true).any { it.parentClassOrNull?.symbol == symbols.childrenBuilderClass }

/** Whether [this] call creates a React component (`FC`, `@ComponentWrapper`, `memo`, `forwardRef`, `lazy`). */
internal fun IrCall.createsComponent(symbols: ReactSymbols): Boolean =
    symbol in symbols.componentFactories ||
        symbol.owner.isComponentWrapper() ||
        symbol.owner.fqNameWhenAvailable in COMPONENT_FACTORIES
