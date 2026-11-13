package com.github.porotkin.reactcompiler.lint

import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrLocalDelegatedProperty
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSpreadElement
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.isGetter
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.FqName

/**
 * [LintRule.REACT_EXHAUSTIVE_DEPS], after `exhaustive-deps` of React's linter: the dependencies of a hook name every
 * value of the component that its callback reads. A value left out stays, inside the callback, what it was on the
 * render that last ran the hook; a `useMemo` or `useCallback` with incomplete dependencies is also one React Compiler
 * cannot keep, so it gives up on the whole function.
 *
 * A hook takes dependencies, by kotlin-react's convention, as a `vararg dependencies` before its callback. What the
 * callback reads is a value declared in the function that calls the hook, with the properties read from it:
 * `props.title` is satisfied by `props.title` or by `props`. As in React's linter, values that never change are not
 * dependencies: the result of `useRef`, the setter of `useState`, an effect event, a constant. A dependency written
 * as anything but a value or its property (`items.size > 0`, `a to b`) covers every value it mentions.
 */
internal class ExhaustiveDeps(
    private val file: IrFile,
    private val lint: LintSymbols,
    private val reporter: LintReporter,
    /** The accessors of `by` locals, which a read or an assignment of the local calls. */
    private val delegated: Map<IrSimpleFunctionSymbol, IrLocalDelegatedProperty>,
    /** The locals whose value is the same on every render. */
    private val stable: Set<IrSymbol>,
) {
    /** Checks [call] of the hook [callee], made in code whose locals belong to the functions in [scope]. */
    fun check(
        call: IrCall,
        callee: IrSimpleFunction,
        scope: Set<IrDeclarationParent>,
    ) {
        val callback = (call.arguments.lastOrNull()?.unwrapCasts() as? IrFunctionExpression)?.function ?: return
        val hook = callee.name
        val parameter = callee.parameters.indexOfFirst { it.isDependencies() }
        val elements = when {
            parameter >= 0 -> (call.arguments[parameter] as? IrVararg)?.elements.orEmpty()
            // A hook that runs once takes no dependencies because it has none.
            callee.fqNameWhenAvailable in RUN_ONCE_HOOKS -> emptyList()
            else -> return
        }
        if (elements.any { it is IrSpreadElement }) {
            // A hook that passes on the dependencies it takes itself is checked where it is called.
            if (elements.all { it is IrSpreadElement && it.expression.isOwnDependencies() }) return
            return report(callback, "Hook `$hook` spreads an array into its dependencies, which cannot be checked. List the dependencies one by one.")
        }

        val declared = mutableListOf<Path>()
        val mentioned = mutableSetOf<IrSymbol>()
        for (element in elements) {
            val path = (element as IrExpression).path(scope)
            if (path != null) declared += path else mentioned += Reads(scope).of(element).map { it.root }
        }
        val read = Reads(scope).of(callback.body ?: return).filterNot { it.isStable }

        val missing = read
            .filter { use -> use.root !in mentioned && declared.none { it.root == use.root && use.startsWith(it) } }
            // `props.item` says all there is to say about `props.item.id`.
            .let { paths -> paths.filter { path -> paths.none { it.root == path.root && it.properties.size < path.properties.size && path.startsWith(it) } } }
            .mapTo(linkedSetOf()) { it.toString() }
        // An effect may depend on more than it reads, to run again; a memoized value may not, React's linter says.
        val unnecessary = declared
            .filter { callee.fqNameWhenAvailable in MEMO_HOOKS && !it.isStable }
            .filter { dependency -> read.none { it.root == dependency.root && (it.startsWith(dependency) || dependency.startsWith(it)) } }
            .mapTo(linkedSetOf()) { it.toString() }

        // One report for both: the compiler keeps a single diagnostic of a kind for one place.
        val problems = listOfNotNull(
            missing.takeIf { it.isNotEmpty() }?.let {
                val (it, changes) = if (it.size == 1) "it" to "it changes" else "them" to "they change"
                "${missing.named("a missing dependency", "missing dependencies")}. Its callback reads $it, so the hook must run again when $changes: add $it to the dependencies."
            },
            unnecessary.takeIf { it.isNotEmpty() }?.let {
                val it = if (it.size == 1) "it" else "them"
                "${unnecessary.named("an unnecessary dependency", "unnecessary dependencies")}. Its callback does not read $it: remove $it from the dependencies."
            },
        )
        if (problems.isNotEmpty()) report(callback, "Hook `$hook` has ${problems.joinToString(" It also has ")}")
    }

    private fun Set<String>.named(
        one: String,
        several: String,
    ): String =
        "${if (size == 1) one else several}: ${joinToString { "`$it`" }}"

    /** A value of the calling function with the properties read from it, e.g. `props.item.id`. */
    private class Path(
        val root: IrSymbol,
        private val rootName: String,
        val properties: List<String>,
        val isStable: Boolean,
    ) {
        fun startsWith(prefix: Path): Boolean =
            properties.size >= prefix.properties.size && properties.subList(0, prefix.properties.size) == prefix.properties

        operator fun plus(property: String): Path =
            Path(root, rootName, properties + property, isStable)

        override fun toString(): String =
            (listOf(rootName) + properties).joinToString(".")
    }

    /** This expression as a [Path] rooted in [scope], if it is a value of the calling function or a property of one. */
    private fun IrExpression.path(scope: Set<IrDeclarationParent>): Path? =
        when (this) {
            is IrGetValue -> {
                val value = symbol.owner
                // A member's own object does not change between its renders, and nothing reads a builder.
                val isReceiver = value is IrValueParameter && value.kind == IrParameterKind.DispatchReceiver
                val name = if (value.name.isSpecial) "this" else value.name.asString()
                Path(symbol, name, emptyList(), symbol in stable).takeIf { value.parent in scope && !isReceiver }
            }
            is IrTypeOperatorCall -> argument.path(scope)
            is IrCall -> {
                val local = delegated[symbol]
                when {
                    // `by useRefValue()` is a ref, and assigning a `by useState()` local calls its setter: neither changes.
                    local != null -> {
                        val isRead = symbol == local.getter.symbol && local.delegate?.type?.let(lint::isRefValue) != true
                        Path(local.symbol, local.name.asString(), emptyList(), !isRead).takeIf { local.parent in scope }
                    }
                    symbol.owner.isGetter -> {
                        val receiver = receiver() ?: return null
                        // As in React's linter, what a callback depends on in `ref.current` is the ref.
                        val property = symbol.owner.correspondingPropertySymbol!!.owner.name.asString()
                        if (property == "current" && lint.isRef(receiver.type)) receiver.path(scope) else receiver.path(scope)?.plus(property)
                    }
                    else -> null
                }
            }
            else -> null
        }

    /** Collects the [Path]s that code reads: each value of [scope], with the longest chain of properties read from it. */
    private inner class Reads(
        private val scope: Set<IrDeclarationParent>,
    ) : IrVisitorVoid() {
        private val paths = mutableListOf<Path>()

        fun of(element: IrElement): List<Path> {
            element.acceptVoid(this)
            return paths
        }

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitGetValue(expression: IrGetValue) {
            expression.path(scope)?.let(paths::add)
        }

        override fun visitCall(expression: IrCall) {
            val path = expression.path(scope)
            if (path != null) paths += path else expression.acceptChildrenVoid(this)
        }
    }

    private fun report(
        callback: IrSimpleFunction,
        message: String,
    ) {
        // On the callback, so that `@Suppress` on the lambda itself silences the hook it is passed to.
        reporter.report(LintRule.REACT_EXHAUSTIVE_DEPS, file, callback, message)
    }
}

/** Whether this is the `vararg dependencies` parameter of the function around it. */
private fun IrExpression.isOwnDependencies(): Boolean =
    ((unwrapCasts() as? IrGetValue)?.symbol?.owner as? IrValueParameter)?.isDependencies() == true

/** kotlin-react's convention: `vararg dependencies: Any?`, before the callback. */
private fun IrValueParameter.isDependencies(): Boolean =
    varargElementType != null && name.asString() in DEPENDENCY_PARAMETERS

private val DEPENDENCY_PARAMETERS = setOf("dependencies", "deps")

private val RUN_ONCE_HOOKS = setOf("useEffectOnce", "useLayoutEffectOnce", "useInsertionEffectOnce").mapTo(mutableSetOf()) { FqName("react.$it") }

private val MEMO_HOOKS = setOf(FqName("react.useMemo"), FqName("react.useCallback"))
