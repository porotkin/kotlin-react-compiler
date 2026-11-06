package com.github.porotkin.reactcompiler

import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A test compiling Kotlin/JS projects with the plugin in a fresh [dir]. */
abstract class PluginTest {
    @TempDir
    lateinit var dir: File

    /** Compiles [declarations] in an `app` module importing `react.*` and returns the module's JavaScript. */
    protected fun compile(declarations: String): String {
        TestProject(dir).apply { module("app", "package app\n\nimport react.*\n\n${declarations.trimIndent()}") }
            .run(":app:compileDevelopmentExecutableKotlinJs")
        return dir.walkTopDown().first { it.name.endsWith("-app.mjs") }.readText()
    }
}
