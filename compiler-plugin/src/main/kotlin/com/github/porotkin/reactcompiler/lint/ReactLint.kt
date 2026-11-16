package com.github.porotkin.reactcompiler.lint

import com.github.porotkin.reactcompiler.ReactSymbols
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

private val RENDER_RULES = LintRule.entries - setOf(
    LintRule.REACT_UNSUPPORTED_SYNTAX,
    LintRule.REACT_ONLY_EXPORT_COMPONENTS,
)

/**
 * Checks the files of a module against the Rules of React, and against what the plugins need of a file: see
 * [LintRule]. It reads the code and reports; the lowerings run after it whatever it finds.
 */
internal class ReactLint(
    private val symbols: ReactSymbols,
    private val reporter: LintReporter,
    private val builtIns: IrBuiltIns,
) {
    fun check(module: IrModuleFragment) {
        val lintSymbols = LintSymbols(symbols)
        val checkRender = RENDER_RULES.any(reporter::isOn)
        for (file in module.files) {
            if (checkRender) RenderWalk(file, symbols, lintSymbols, reporter, builtIns).check(file)
            if (reporter.isOn(LintRule.REACT_HOOKS_AS_VALUES)) HookDeclarations(file, reporter).check()
            if (reporter.isOn(LintRule.REACT_ONLY_EXPORT_COMPONENTS)) ComponentExports(file, symbols, reporter).check()
        }
    }
}
