package com.github.porotkin.reactcompiler

import org.junit.jupiter.api.Test

class FileLintTest : LintTest() {
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
            fileAnnotations = """@file:Suppress("REACT_RULES_OF_HOOKS")""",
        )
    }
}
