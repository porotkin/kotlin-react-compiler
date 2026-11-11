package com.github.porotkin.reactcompiler

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.KtSourcelessDiagnosticFactory
import org.jetbrains.kotlin.diagnostics.errorWithoutSource
import org.jetbrains.kotlin.diagnostics.infoWithoutSource
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.BaseSourcelessDiagnosticRendererFactory
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile

/** Fails the compilation with [message], at the position of [at] in [file]. */
internal fun IrPluginContext.reportError(
    file: IrFile,
    at: IrElement,
    message: String,
) {
    report(ReactCompilerDiagnostics.REACT_COMPILER_ERROR, file, at, message)
}

/** Notes [message] at the position of [at] in [file]; Gradle prints it when run with `--info`. */
internal fun IrPluginContext.reportInfo(
    file: IrFile,
    at: IrElement,
    message: String,
) {
    report(ReactCompilerDiagnostics.REACT_COMPILER_INFO, file, at, message)
}

private fun IrPluginContext.report(
    factory: KtSourcelessDiagnosticFactory,
    file: IrFile,
    at: IrElement,
    message: String,
) {
    val entry = file.fileEntry
    val location = CompilerMessageLocation.create(
        entry.name,
        entry.getLineNumber(at.startOffset) + 1,
        entry.getColumnNumber(at.startOffset) + 1,
        null,
    )
    diagnosticReporter.report(factory, message, location)
}

private object ReactCompilerDiagnostics : KtDiagnosticsContainer() {
    val REACT_COMPILER_ERROR by errorWithoutSource()
    val REACT_COMPILER_INFO by infoWithoutSource()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory = Messages

    object Messages : BaseSourcelessDiagnosticRendererFactory() {
        override val MAP by KtDiagnosticFactoryToRendererMap("ReactCompiler") { map ->
            map.put(REACT_COMPILER_ERROR, MESSAGE_PLACEHOLDER)
            map.put(REACT_COMPILER_INFO, MESSAGE_PLACEHOLDER)
        }
    }
}
