package com.github.porotkin.reactcompiler.lint

import com.github.porotkin.reactcompiler.ReactSymbols
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithVisibility
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.util.isEffectivelyExternal
import org.jetbrains.kotlin.ir.util.isSubtypeOfClass

/**
 * [LintRule.REACT_ONLY_EXPORT_COMPONENTS]: a file that exports a component exports nothing else.
 *
 * Fast Refresh updates a component in place, keeping its state, only when the module of the component exports that
 * component alone: with one JavaScript module per Kotlin file, a second export makes an edit of the file re-run the
 * modules that import it, or reload the page. A file exports its top-level declarations that are not `private` and
 * exist at run time, which leaves out external declarations and type aliases. Its component is the first exported
 * property typed `FC`; every other export of the file is reported.
 */
internal class ComponentExports(
    private val file: IrFile,
    private val symbols: ReactSymbols,
    private val reporter: LintReporter,
) {
    fun check() {
        val exports = file.declarations.filter { it.isExported() }
        val component = exports.firstOrNull { it.isComponent() } ?: return
        for (export in exports) {
            if (export == component) continue
            val name = (export as IrDeclarationWithName).name
            val problem = if (export.isComponent()) "is a second component exported from this file" else "is exported from a file that exports the component `${(component as IrProperty).name}`"
            val message = "`$name` $problem, which turns Fast Refresh off for the file. Make it private, or move it to a file of its own."
            reporter.report(LintRule.REACT_ONLY_EXPORT_COMPONENTS, file, export, message)
        }
    }

    private fun IrDeclaration.isExported(): Boolean {
        val isRuntimeDeclaration = this is IrProperty || this is IrSimpleFunction || this is IrClass
        return isRuntimeDeclaration && (this as IrDeclarationWithVisibility).visibility != DescriptorVisibilities.PRIVATE && !isEffectivelyExternal()
    }

    private fun IrDeclaration.isComponent(): Boolean =
        this is IrProperty && (getter?.returnType ?: backingField?.type)?.isSubtypeOfClass(symbols.fcClass) == true
}
