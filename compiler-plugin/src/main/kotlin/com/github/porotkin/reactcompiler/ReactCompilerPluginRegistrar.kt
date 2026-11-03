package com.github.porotkin.reactcompiler

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CompilerConfiguration

class ReactCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = "io.github.porotkin.kotlin-react-compiler"

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(
        configuration: CompilerConfiguration,
    ) {
        IrGenerationExtension.registerExtension(ReactCompilerIrGenerationExtension())
    }
}
