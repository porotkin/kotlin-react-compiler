package com.github.porotkin.reactcompiler.component

import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.any
import com.github.porotkin.reactcompiler.isHookName
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isUnit
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.name.Name

/**
 * The `private fun ChildrenBuilder.Foo(props)` helpers of [file] whose bodies can be lowered like a component.
 * A lowered component calls a lowered copy taking only `props`, which replaces the original once nothing else
 * calls it, and is otherwise added next to it as `Foo$lowered`.
 */
internal class ComponentHelpers(
    private val file: IrFile,
    symbols: ReactSymbols,
    /** Lowers a body read by [analyzer]: drops its builder parameter and writes the items back. */
    private val rewrite: (IrSimpleFunction, IrValueParameter, List<Item>) -> Unit,
) {
    private val helpers: Map<IrSimpleFunctionSymbol, Helper>
    private val rewritten = mutableSetOf<IrSimpleFunctionSymbol>()

    /** Reads component bodies, with calls to this file's lowerable helpers read as children. */
    val analyzer: ComponentAnalyzer

    init {
        // Whether a helper can be lowered depends on which helpers it calls can: shrink the candidates to a fixed point.
        // The copies are analyzed, so a recursive helper, whose copy calls the copy, is never lowerable.
        var candidates = file.declarations
            .filterIsInstance<IrSimpleFunction>()
            .filter { it.isHelperCandidate(symbols) }
            .map { Helper(it, it.deepCopyWithSymbols(file), emptyList()) }
        do {
            val count = candidates.size
            val analyzer = ComponentAnalyzer(symbols, candidates.mapTo(mutableSetOf()) { it.original.symbol })
            candidates = candidates.mapNotNull { helper ->
                analyzer.analyze(helper.lowered, helper.lowered.parameters.first().symbol)?.let { helper.copy(items = it) }
            }
        } while (candidates.size < count)
        helpers = candidates.associateBy { it.original.symbol }
        analyzer = ComponentAnalyzer(symbols, helpers.keys)
    }

    /** The lowered copy of [helper], written on its first call. */
    fun resolve(helper: IrSimpleFunctionSymbol): IrSimpleFunctionSymbol {
        val (_, lowered, items) = helpers.getValue(helper)
        if (rewritten.add(helper)) rewrite(lowered, lowered.parameters.first(), items)
        return lowered.symbol
    }

    /**
     * Puts each lowered copy in the file, in place of its original or, while the original is still called, next to it,
     * and gives the copies that call hooks a hook name.
     */
    fun install() {
        for (symbol in rewritten) {
            val (original, lowered) = helpers.getValue(symbol)
            val index = file.declarations.indexOf(original)
            val stillCalled = file.declarations.any { it != original && it.any { e -> e is IrCall && e.symbol == symbol } }
            if (stillCalled) {
                lowered.name = Name.identifier("${original.name}\$lowered")
                file.declarations.add(index + 1, lowered)
            } else {
                file.declarations[index] = lowered
            }
        }
        nameHooks()
    }

    /**
     * A lowered copy runs its hooks in its caller's render, so to React Compiler it is a custom hook, which it only
     * recognizes by a `use` name. Under any other name it memoizes the call on its arguments: a re-render from the
     * copy's own state or a context then skips its hooks and React drops their state. Copies without hooks keep their
     * name, so a caller may still call them conditionally. Renaming to a fixed point covers hooks reached through
     * other copies.
     */
    private fun nameHooks() {
        val pending = rewritten.map { helpers.getValue(it).lowered }.filterTo(mutableListOf()) { !it.name.isHookName() }
        do {
            val hooks = pending.filter { copy -> copy.any { it is IrCall && it.symbol.owner.name.isHookName() } }
            for (hook in hooks) {
                hook.name = Name.identifier("use" + hook.name.asString().replaceFirstChar(Char::uppercaseChar))
            }
            pending -= hooks
        } while (hooks.isNotEmpty())
    }
}

private data class Helper(
    val original: IrSimpleFunction,
    /** A copy of [original], rewritten on its first call. */
    val lowered: IrSimpleFunction,
    val items: List<Item>,
)

private fun IrSimpleFunction.isHelperCandidate(symbols: ReactSymbols): Boolean {
    val receiver = parameters.firstOrNull() ?: return false
    return visibility == DescriptorVisibilities.PRIVATE &&
        returnType.isUnit() &&
        receiver.kind == IrParameterKind.ExtensionReceiver &&
        receiver.type.classOrNull == symbols.childrenBuilderClass
}
