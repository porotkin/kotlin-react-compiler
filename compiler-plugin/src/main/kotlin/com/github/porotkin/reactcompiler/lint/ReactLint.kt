package com.github.porotkin.reactcompiler.lint

import com.github.porotkin.reactcompiler.ReactSymbols
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/**
 * Checks the files of a module against the Rules of React: see [LintRule]. It reads the code and reports; the
 * lowerings run after it whatever it finds.
 */
internal class ReactLint(
    private val symbols: ReactSymbols,
    private val lintSymbols: LintSymbols,
    private val reporter: LintReporter,
) {
    fun check(module: IrModuleFragment) {
        for (file in module.files) {
            RenderRules(file, symbols, lintSymbols, reporter).check()
        }
    }
}
