package com.github.porotkin.reactcompiler

import com.github.porotkin.reactcompiler.lint.LintConfig
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

class ReactCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(
        configuration: CompilerConfiguration,
    ) {
        // The command line processor has already rejected the values that cannot be read.
        val lint = LintConfig.parse(configuration.getList(LINT_OPTIONS)) {}
        IrGenerationExtension.registerExtension(ReactCompilerIrGenerationExtension(lint))
    }
}
