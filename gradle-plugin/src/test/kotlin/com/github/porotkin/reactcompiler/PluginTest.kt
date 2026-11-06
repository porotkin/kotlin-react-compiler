package com.github.porotkin.reactcompiler

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** A test compiling Kotlin/JS projects with the plugin in a fresh [dir]. */
abstract class PluginTest {
    @TempDir
    lateinit var dir: File

    /** Runs [script] in Node with `m` bound to the compiled [module] and stub `react` / generic prop setters installed. */
    protected fun runNode(
        module: String,
        script: String,
    ): String {
        val mjsFile = dir.walkTopDown().first { it.name.endsWith("-$module.mjs") }
        val reactDir = File(mjsFile.parentFile, "node_modules/react").apply { mkdirs() }
        File(reactDir, "package.json").writeText("""{"name":"react","type":"module","main":"index.mjs"}""")
        File(reactDir, "index.mjs").writeText(
            """
            export const Fragment = 'Fragment';
            export const useState = (v) => [v, () => {}];
            export const useMemo = (fn) => fn();
            export const useCallback = (fn) => fn;
            """.trimIndent(),
        )
        val accessors = mjsFile.parentFile.listFiles { f -> f.name.endsWith(".mjs") }.orEmpty()
            .flatMap { Regex("""(?:get|set)_(?:children|key|value|setter)_[\w$]+""").findAll(it.readText()).map { m -> m.value }.toList() }
            .toSet()
        val runnerFile = File(mjsFile.parentFile, "run-test.mjs")
        runnerFile.writeText(
            """
            for (const method of ${accessors.joinToString(prefix = "[", postfix = "]") { "'$it'" }}) {
              const [, kind, prop] = method.match(/^(get|set)_(children|key|value|setter)_/);
              Object.prototype[method] = kind === 'get' ? function() { return this[prop]; } : function(v) { this[prop] = v; };
            }
            const m = await import('./${mjsFile.name}');
            $script
            """.trimIndent(),
        )
        val process = ProcessBuilder("node", runnerFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        assertTrue(process.waitFor() == 0, "Expected Node execution to succeed, got:\n$output")
        return output
    }

    /** Compiles [declarations] in an `app` module importing `react.*` and returns the module's JavaScript. */
    protected fun compile(declarations: String): String {
        TestProject(dir).apply { module("app", "package app\n\nimport react.*\n\n${declarations.trimIndent()}") }
            .run(":app:compileDevelopmentExecutableKotlinJs")
        return dir.walkTopDown().first { it.name.endsWith("-app.mjs") }.readText()
    }
}
