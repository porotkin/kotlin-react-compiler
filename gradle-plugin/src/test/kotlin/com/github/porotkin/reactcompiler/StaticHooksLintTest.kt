package com.github.porotkin.reactcompiler

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StaticHooksLintTest : LintTest() {
    @Test
    fun `reports hooks kept in values, declared as members, and hook names on other values`() {
        val result = assertLint(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

            fun useValue(): Int = 1

            class Loader(
                val useData: () -> Int, // ! REACT_HOOKS_AS_VALUES
                val useCache: Boolean,
            )

            external interface Options {
                var useTransition: Boolean
                var useItems: () -> Int // ! REACT_HOOKS_AS_VALUES
            }

            val useStored: () -> Int = { 1 } // ! REACT_HOOKS_AS_VALUES

            class Instruments {
                fun useData(value: Int) {} // ! REACT_HOOKS_AS_VALUES
            }

            interface Source {
                fun useSource(): Int // ! REACT_HOOKS_AS_VALUES
            }

            object Fixed : Source {
                override fun useSource(): Int = 1
            }

            val References: FC<Props> = FC {
                val loader = Loader(::useValue, true) // ! REACT_HOOKS_AS_VALUES
                fun useLocal(): Int = 1 // ! REACT_HOOKS_AS_VALUES
                div { +"${'$'}loader ${'$'}{useLocal()}" }
            }

            fun wrapper(useTransition: Boolean, options: Options): FC<Props> = // ! REACT_HOOKS_AS_VALUES
                FC {
                    val useShort = options.useTransition && useTransition // ! REACT_HOOKS_AS_VALUES REACT_HOOKS_AS_VALUES
                    div { +"${'$'}useShort" }
                }

            // Outside components and hooks React Compiler does not read the names.
            fun plain(useCache: Boolean, loader: Loader): Boolean {
                val useFast = useCache && loader.useCache
                return useFast
            }
            """,
        )

        assertTrue("Hook `useValue` is referenced as a value" in result.output, result.output)
        assertTrue("`useData` keeps a hook in a value" in result.output, result.output)
        assertTrue("Hook `useData` is a member of a class" in result.output, result.output)
        assertTrue("Hook `useLocal` is a local function" in result.output, result.output)
        assertTrue("`useTransition` is not a hook, but React Compiler takes every name that starts with `use` for one." in result.output, result.output)
    }
}
