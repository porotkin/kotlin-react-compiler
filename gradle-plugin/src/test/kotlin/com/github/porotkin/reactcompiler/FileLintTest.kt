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
