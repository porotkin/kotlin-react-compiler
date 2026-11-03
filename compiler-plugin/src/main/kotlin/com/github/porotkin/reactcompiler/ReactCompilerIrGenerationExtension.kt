package com.github.porotkin.reactcompiler

import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.isJs

private val REACT = FqName("react")
private val HOOK_NAME = Regex("^use[A-Z0-9]")
private const val USE_MEMO = "\"use memo\""

class ReactCompilerIrGenerationExtension : IrGenerationExtension {
    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
    ) {
        if (!pluginContext.platform.isJs()) return
        val file = moduleFragment.files.firstOrNull() ?: return
        val finder = pluginContext.finderForSource(file)
        if (finder.findClass(ClassId(REACT, Name.identifier("FragmentProps"))) == null) return
        val transformer = ReactCompilerTransformer(ReactSymbols(pluginContext, finder))
        moduleFragment.files.forEach { it.transform(transformer, null) }
    }
}

private class ReactSymbols(
    val context: IrPluginContext,
    private val finder: DeclarationFinder,
) {
    val js = function(FqName("kotlin.js"), "js")

    private fun callable(
        packageName: FqName,
        name: String,
    ) = CallableId(packageName, Name.identifier(name))

    private fun function(
        packageName: FqName,
        name: String,
        filter: (IrSimpleFunctionSymbol) -> Boolean = { true },
    ): IrSimpleFunctionSymbol =
        functions(packageName, name, filter).first()

    private fun functions(
        packageName: FqName,
        name: String,
        filter: (IrSimpleFunctionSymbol) -> Boolean = { true },
    ): List<IrSimpleFunctionSymbol> =
        finder.findFunctions(callable(packageName, name)).filter(filter)
            .ifEmpty { error("React Compiler IR plugin: missing $packageName.$name") }
}

private class ReactCompilerTransformer(
    private val symbols: ReactSymbols,
) : IrElementTransformerVoid() {
    private val context = symbols.context

    override fun visitSimpleFunction(declaration: IrSimpleFunction): IrStatement {
        declaration.transformChildrenVoid(this)
        val body = declaration.body as? IrBlockBody
        if (
            body != null &&
            !declaration.isInline &&
            HOOK_NAME.containsMatchIn(declaration.name.asString()) &&
            body.statements.firstOrNull()?.isDirective() != true
        ) {
            body.statements.add(0, builder(declaration.symbol).jsCode(USE_MEMO))
        }
        return declaration
    }

    private fun builder(symbol: IrSymbol) =
        DeclarationIrBuilder(context, symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)

    private fun DeclarationIrBuilder.jsCode(code: String): IrExpression =
        irCall(symbols.js).apply { arguments[0] = irString(code) }

    private fun IrStatement.isDirective(): Boolean {
        val call = unwrapCoercion() as? IrCall ?: return false
        return call.symbol == symbols.js && (call.arguments[0] as? IrConst)?.value == USE_MEMO
    }
}

private fun IrStatement.unwrapCoercion(): IrStatement =
    if (this is IrTypeOperatorCall && operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT) argument else this
