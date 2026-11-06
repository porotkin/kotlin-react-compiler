package com.github.porotkin.reactcompiler.hook

import com.github.porotkin.reactcompiler.ReactLowering
import com.github.porotkin.reactcompiler.ReactSymbols
import com.github.porotkin.reactcompiler.irUseMemo
import com.github.porotkin.reactcompiler.isUseMemo
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

private val HOOK_NAME = Regex("^use[A-Z0-9]")

/** Prepends `"use memo"` to non-inline `use*` functions so React Compiler compiles them in `annotation` mode. */
internal class HookDirectiveLowering(
    context: IrPluginContext,
    symbols: ReactSymbols,
) : ReactLowering(context, symbols) {
    override fun visitFunctionNew(declaration: IrFunction): IrStatement {
        declaration.transformChildrenVoid(this)
        val body = declaration.body as? IrBlockBody ?: return declaration
        if (declaration !is IrSimpleFunction || declaration.isInline || !HOOK_NAME.containsMatchIn(declaration.name.asString())) return declaration
        if (body.statements.firstOrNull()?.isUseMemo(symbols) == true) return declaration
        body.statements.add(0, builder(declaration.symbol).irUseMemo(symbols))
        return declaration
    }
}
