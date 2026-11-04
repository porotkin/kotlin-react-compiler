package com.github.porotkin.reactcompiler

import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * One IR pass of the plugin, run over every file of the module.
 * Add a pass by subclassing this and registering it in [ReactCompilerIrGenerationExtension].
 */
internal abstract class ReactLowering(
    protected val context: IrPluginContext,
    protected val symbols: ReactSymbols,
) : IrElementTransformerVoid(),
    FileLoweringPass {
    override fun lower(irFile: IrFile) {
        irFile.transformChildrenVoid(this)
    }

    protected fun builder(symbol: IrSymbol): DeclarationIrBuilder =
        DeclarationIrBuilder(context, symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)
}
