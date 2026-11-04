package com.github.porotkin.reactcompiler

import com.github.porotkin.reactcompiler.component.ComponentLowering
import com.github.porotkin.reactcompiler.hook.HookCallLowering
import com.github.porotkin.reactcompiler.hook.HookDirectiveLowering
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.platform.isJs

/**
 * Runs the plugin's [ReactLowering]s on Kotlin/JS modules that use kotlin-react.
 *
 * Hook passes run before [ComponentLowering] so a component body is analyzed with its hook calls already
 * in their final form, the same order a single post-order traversal gives.
 */
class ReactCompilerIrGenerationExtension : IrGenerationExtension {
    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
    ) {
        if (!pluginContext.platform.isJs()) return
        val file = moduleFragment.files.firstOrNull() ?: return
        val symbols = ReactSymbols.resolve(pluginContext.finderForSource(file)) ?: return
        listOf(
            HookCallLowering(pluginContext, symbols),
            HookDirectiveLowering(pluginContext, symbols),
            ComponentLowering(pluginContext, symbols),
        ).forEach { it.lower(moduleFragment) }
    }
}
