package com.github.porotkin.reactcompiler

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * A test of the linter. Its source marks the lines the linter must report: `// ! RULE` for an error and `// ? RULE`
 * for a warning, one name for each report of the line.
 */
abstract class LintTest : PluginTest() {
    /**
     * Compiles [declarations] in an `app` module importing `react.*`, with [fileAnnotations] at the top of the file,
     * and checks that the linter reports exactly what the source expects.
     */
    protected fun assertLint(
        declarations: String,
        fileAnnotations: String = "",
    ): BuildResult {
        val source = listOf(fileAnnotations, "package app\n\nimport react.*\n\n${declarations.trimIndent()}\n")
            .filter { it.isNotEmpty() }.joinToString("\n")
        val result = TestProject(dir).apply { module("app", source) }
            .runUnchecked(":app:compileDevelopmentExecutableKotlinJs")
        val expected = source.lines().flatMapIndexed { index, line ->
            val marker = MARKER.find(line) ?: return@flatMapIndexed emptyList()
            val level = if (marker.groupValues[1] == "!") "e" else "w"
            marker.groupValues[2].trim().split(' ').map { "$level ${index + 1} $it" }
        }
        val reported = result.output.lines().mapNotNull { line ->
            REPORT.find(line)?.destructured?.let { (level, number, rule) -> "$level $number $rule" }
        }
        assertEquals(expected.sorted().joinToString("\n"), reported.sorted().joinToString("\n")) {
            "Expected the linter to report the marked lines, got:\n${result.output.lines().filter { REPORT in it }.joinToString("\n")}\n"
        }
        // A source that does not compile never reaches the linter, and would pass for one it has nothing to say about.
        val otherErrors = result.output.lines().filter { it.startsWith("e: ") && REPORT !in it }
        assertTrue(otherErrors.isEmpty()) { "Expected no errors but the linter's, got:\n${otherErrors.joinToString("\n")}" }
        val outcome = result.task(":app:compileKotlinJs")?.outcome
        val expectedOutcome = if (expected.any { it.startsWith("e ") }) TaskOutcome.FAILED else TaskOutcome.SUCCESS
        assertEquals(expectedOutcome, outcome) { "Unexpected outcome of the compilation:\n${result.output}" }
        return result
    }
}

private val MARKER = Regex("""// ([!?]) ((?:REACT_[A-Z_]+ ?)+)$""")

/** `e: file:///.../App.kt:12:5 Message. [REACT_RULE]` */
private val REPORT = Regex("""^([ew]): \S+/App\.kt:(\d+):\d+ .*\[(REACT_[A-Z_]+)]$""")
