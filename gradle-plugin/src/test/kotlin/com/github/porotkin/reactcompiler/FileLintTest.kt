package com.github.porotkin.reactcompiler

import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FileLintTest : LintTest() {
    @Test
    fun `warns about what a file exports next to its component`() {
        val result = assertLint(
            """
            private val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

            external interface Theme {
                var name: String
            }

            typealias CardComponent = FC<Props>

            val Card: FC<Props> = FC {
                div { +"card" }
            }

            val CardHeader: FC<Props> = FC { // ? REACT_ONLY_EXPORT_COMPONENTS
                div { +"header" }
            }

            internal val CardFooter: CardComponent = FC { // ? REACT_ONLY_EXPORT_COMPONENTS
                div { +"footer" }
            }

            private val CardBody: FC<Props> = FC {
                div { +"body" }
            }

            const val CARD_WIDTH: Int = 100 // ? REACT_ONLY_EXPORT_COMPONENTS

            internal fun cardTitle(): String = "title" // ? REACT_ONLY_EXPORT_COMPONENTS

            fun ChildrenBuilder.CardIcon() { // ? REACT_ONLY_EXPORT_COMPONENTS
                div { +"icon" }
            }

            enum class CardKind { // ? REACT_ONLY_EXPORT_COMPONENTS
                PLAIN,
            }

            class CardState // ? REACT_ONLY_EXPORT_COMPONENTS

            private fun helper(): FC<Props> = CardBody
            """,
        )

        assertEquals(TaskOutcome.SUCCESS, result.task(":app:compileKotlinJs")?.outcome)
        assertTrue("`CardHeader` is a second component exported from this file" in result.output, result.output)
        assertTrue("`cardTitle` is exported from a file that exports the component `Card`" in result.output, result.output)

        // Without a component a file exports what it likes.
        assertLint(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

            const val WIDTH: Int = 100

            fun title(): String = "title"
            """,
        )
    }

    @Test
    fun `warns about a component the plugin cannot lower`() {
        val result = assertLint(
            """
            private fun fill(builder: ChildrenBuilder) {}

            val Escapes: FC<Props> = FC {
                fill(this) // ? REACT_UNSUPPORTED_SYNTAX
            }

            private val OptedOut: FC<Props> = FC {
                js("\"use no memo\"")
                fill(this)
            }

            @Suppress("REACT_UNSUPPORTED_SYNTAX")
            private val Suppressed: FC<Props> = FC {
                fill(this)
            }
            """,
        )

        assertTrue("The plugin cannot lower this code" in result.output, result.output)
    }

    @Test
    fun `sets rule levels from the build script`() {
        val source = """
            private val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

            private fun useValue(): Int = 1

            val Conditional: FC<Props> = FC {
                if (div.toString().isEmpty()) useValue() // %HOOKS%
            }

            fun title(): String = "title" // %EXPORTS%
        """

        fun lint(
            hooks: String,
            exports: String,
            configuration: String,
        ) = assertLint(source.replace("// %HOOKS%", hooks).replace("// %EXPORTS%", exports), configuration)

        // The compiler prints no warnings for a compilation that has errors.
        lint("// ! REACT_RULES_OF_HOOKS", "", "")
        lint("", "// ? REACT_ONLY_EXPORT_COMPONENTS", """reactCompiler { lint { off("REACT_RULES_OF_HOOKS") } }""")
        lint("", "// ! REACT_ONLY_EXPORT_COMPONENTS", """reactCompiler { lint { warning("REACT_RULES_OF_HOOKS"); error("REACT_ONLY_EXPORT_COMPONENTS") } }""")
        lint(
            "// ? REACT_RULES_OF_HOOKS",
            "",
            """reactCompiler { lint { all(com.github.porotkin.reactcompiler.ReactLintLevel.WARNING); off("REACT_ONLY_EXPORT_COMPONENTS") } }""",
        )
        val disabled = lint("", "", "reactCompiler { lint { enabled = false } }")
        assertEquals(TaskOutcome.SUCCESS, disabled.task(":app:compileKotlinJs")?.outcome)

        val unknown = TestProject(dir)
            .apply { module("app", "package app", configuration = """reactCompiler { lint { off("REACT_NO_SUCH_RULE") } }""") }
            .runAndFail(":app:compileKotlinJs")
        assertTrue("Invalid value of the lint option: REACT_NO_SUCH_RULE:off" in unknown.output, unknown.output)
    }

    @Test
    fun `suppresses a rule on a statement, a declaration and a file`() {
        assertLint(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

            fun useValue(): Int = 1

            val OnStatement: FC<Props> = FC {
                if (div.toString().isEmpty()) {
                    @Suppress("REACT_RULES_OF_HOOKS")
                    val value = useValue()
                    val other = useValue() // ! REACT_RULES_OF_HOOKS
                    div { +"${'$'}value ${'$'}other" }
                }
            }

            @Suppress("REACT_RULES_OF_HOOKS")
            val OnDeclaration: FC<Props> = FC {
                if (div.toString().isEmpty()) useValue()
            }
            """,
        )
        assertLint(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

            fun useValue(): Int = 1

            val Conditional: FC<Props> = FC {
                if (div.toString().isEmpty()) useValue()
            }
            """,
            fileAnnotations = """@file:Suppress("REACT_RULES_OF_HOOKS", "REACT_ONLY_EXPORT_COMPONENTS")""",
        )
    }
}
