package com.github.porotkin.reactcompiler

import com.github.porotkin.reactcompiler.lint.LintConfig
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CliOptionProcessingException
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey

internal const val PLUGIN_ID = "io.github.porotkin.kotlin-react-compiler"

/** The values of the `lint` option, in the order they were passed. */
internal val LINT_OPTIONS: CompilerConfigurationKey<List<String>> = CompilerConfigurationKey.create("React lint rule levels")

class ReactCompilerCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = listOf(LINT)

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        if (option != LINT) throw CliOptionProcessingException("Unknown plugin option: ${option.optionName}")
        LintConfig.parse(listOf(value)) { throw CliOptionProcessingException("Invalid value of the ${LINT.optionName} option: $it") }
        configuration.appendList(LINT_OPTIONS, value)
    }

    private companion object {
        val LINT = CliOption(
            optionName = "lint",
            valueDescription = "${LintConfig.OFF}|${LintConfig.ALL}:<level>|<RULE>:<level>",
            description = "Level of the Rules of React lint rules: error, warning or off",
            required = false,
            allowMultipleOccurrences = true,
        )
    }
}
