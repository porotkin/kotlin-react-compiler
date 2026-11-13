package com.github.porotkin.reactcompiler

import com.github.porotkin.reactcompiler.component.ComponentLowering
import com.github.porotkin.reactcompiler.hook.HookCallLowering
import com.github.porotkin.reactcompiler.hook.HookDirectiveLowering
import com.github.porotkin.reactcompiler.lint.LintConfig
import com.github.porotkin.reactcompiler.lint.LintReporter
import com.github.porotkin.reactcompiler.lint.ReactLint
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.platform.isJs

/**
 * Checks Kotlin/JS modules that use kotlin-react against the Rules of React ([ReactLint]), then runs the plugin's
 * [ReactLowering]s on them.
 *
 * The linter runs first, on the code as it was written. Hook passes run before [ComponentLowering] so a component
 * body is analyzed with its hook calls already in their final form, the same order a single post-order traversal
 * gives.
 */
class ReactCompilerIrGenerationExtension : IrGenerationExtension {
    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
    ) {
        if (!pluginContext.platform.isJs()) return
        val file = moduleFragment.files.firstOrNull() ?: return
        val symbols = ReactSymbols.resolve(pluginContext.finderForSource(file)) ?: return
        // Every lint rule at its default level.
        val reporter = LintReporter(pluginContext, LintConfig(emptyMap()))
        ReactLint(symbols, reporter).check(moduleFragment)
        listOf(
            HookCallLowering(pluginContext, symbols),
            HookDirectiveLowering(pluginContext, symbols),
            ComponentLowering(pluginContext, symbols),
        ).forEach { it.lower(moduleFragment) }
    }
}
