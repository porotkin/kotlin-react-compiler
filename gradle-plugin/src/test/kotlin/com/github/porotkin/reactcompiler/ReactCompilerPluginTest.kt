package com.github.porotkin.reactcompiler

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReactCompilerPluginTest : PluginTest() {
    @Test
    fun `lowers FC components, rewrites hooks, and injects use memo directive`() {
        val project = TestProject(dir).apply {
            module(
                "app",
                """
                package app

                import react.*

                interface CounterProps : Props {
                    var title: String
                    var step: Int
                }

                val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()
                val span: ElementType<FragmentProps> = "span".unsafeCast<ElementType<FragmentProps>>()

                fun useStepLabel(title: String, count: Int): String =
                    useMemo(title, count) { "${'$'}title: ${'$'}count" }

                val Counter: FC<CounterProps> = FC { props ->
                    val state = ReactModule.useState(0)
                    val label = useStepLabel(props.title, state[0] as Int)
                    val onClick = useCallback(props.step) {
                        props.step + 1
                    }

                    div {
                        span {
                            +label
                        }
                    }
                }

                fun main() {
                    println(Counter)
                }
                """,
            )
        }

        project.run(":app:compileDevelopmentExecutableKotlinJs")

        val mjs = dir.walkTopDown().first { it.extension == "mjs" && "Counter" in it.readText() }.readText()
        assertTrue("'use memo';" in mjs, "Expected 'use memo' directive in:\n$mjs")
        assertTrue("useMemoRaw(" in mjs && "useCallbackRaw(" in mjs, "Expected raw hook calls in:\n$mjs")
        assertTrue("function Counter\$lambda(props)" in mjs, "Expected lowered 1-arg component in:\n$mjs")
        assertTrue("jsx(" in mjs, "Expected lowered jsx call in:\n$mjs")
        assertTrue("@rdk/" in mjs, "Expected @rdk/ source key in:\n$mjs")
    }

    @Test
    fun `warns on a Kotlin version it is not built for`() {
        val project = TestProject(dir, kotlinVersion = "2.4.10").apply {
            module("app", "package app\nfun main() = Unit")
        }

        assertTrue("not 2.4.10, and may fail." in project.run(":app:help").output)
    }
}
