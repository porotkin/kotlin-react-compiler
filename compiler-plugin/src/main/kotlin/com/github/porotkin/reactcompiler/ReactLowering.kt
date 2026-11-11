package com.github.porotkin.reactcompiler

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.IrElementTransformerVoidWithContext
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.symbols.IrSymbol

/**
 * One IR pass of the plugin, run over every file of the module.
 * Add a pass by subclassing this and registering it in [ReactCompilerIrGenerationExtension].
 */
internal abstract class ReactLowering(
    protected val context: IrPluginContext,
    protected val symbols: ReactSymbols,
) : IrElementTransformerVoidWithContext(),
    FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.transform(this, null)
    }

    /** A builder of the nodes that replace [source]: they take its position, which source maps lead back to. */
    protected fun builder(
        symbol: IrSymbol,
        source: IrElement,
    ): DeclarationIrBuilder =
        DeclarationIrBuilder(context, symbol, source.startOffset, source.endOffset)
}
