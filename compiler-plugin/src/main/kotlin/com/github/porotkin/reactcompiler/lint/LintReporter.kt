package com.github.porotkin.reactcompiler.lint

import org.jetbrains.kotlin.KtOffsetsOnlySourceElement
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactory1
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.Severity
import org.jetbrains.kotlin.diagnostics.SourceElementPositioningStrategies
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.CommonRenderers
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile

/**
 * Reports broken [LintRule]s at the level [config] gives them. A report is a compiler diagnostic named after its
 * rule and placed on the code that breaks it, so `@Suppress("<RULE>")` on any enclosing declaration silences it.
 */
internal class LintReporter(
    private val context: IrPluginContext,
    private val config: LintConfig,
) {
    fun isOn(rule: LintRule): Boolean =
        config.level(rule) != LintLevel.OFF

    fun report(
        rule: LintRule,
        file: IrFile,
        at: IrElement,
        message: String,
    ) {
        val factory = LintDiagnostics.factory(rule, config.level(rule)) ?: return
        // Code the compiler generated has no position of its own: such a report goes to the start of the file.
        val source = if (at.startOffset >= 0) KtOffsetsOnlySourceElement(at.startOffset, at.endOffset) else KtOffsetsOnlySourceElement(0, 0)
        // The compiler prints a diagnostic without its name, which is what a reader needs to suppress or configure it.
        context.diagnosticReporter.at(source, at, file).report(factory, "$message [${rule.name}]")
    }
}

private object LintDiagnostics : KtDiagnosticsContainer() {
    private val errors = LintRule.entries.associateWith { factory(it, Severity.ERROR) }
    private val warnings = LintRule.entries.associateWith { factory(it, Severity.WARNING) }

    fun factory(
        rule: LintRule,
        level: LintLevel,
    ): KtDiagnosticFactory1<String>? =
        when (level) {
            LintLevel.ERROR -> errors.getValue(rule)
            LintLevel.WARNING -> warnings.getValue(rule)
            LintLevel.OFF -> null
        }

    /** One rule has an error and a warning factory under the same name, so `@Suppress` does not depend on the level. */
    private fun factory(
        rule: LintRule,
        severity: Severity,
    ): KtDiagnosticFactory1<String> =
        KtDiagnosticFactory1(rule.name, severity, SourceElementPositioningStrategies.DEFAULT, PsiElement::class, Messages)

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Messages

    object Messages : BaseDiagnosticRendererFactory() {
        override val MAP by KtDiagnosticFactoryToRendererMap("ReactLint") { map ->
            (errors.values + warnings.values).forEach { map.put(it, "{0}", CommonRenderers.STRING) }
        }
    }
}
