package com.github.porotkin.reactcompiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HookLoweringTest : PluginTest() {
    @Test
    fun `passes an empty dependency array to a useMemo without dependencies`() {
        val mjs = compile(
            """
            fun useHeader(): String = useMemo { "Results" }
            """,
        )

        assertTrue("useMemoRaw(useHeader\$lambda, [])" in mjs, "Expected empty dependency array in:\n$mjs")
    }

    @Test
    fun `passes a hook callback that captures values as an inline function`() {
        val mjs = compile(
            """
            fun useLabel(title: String): String =
                useMemo(title) { title + "!" }
            """,
        )

        // React Compiler rejects `useMemo(useLabel${'$'}lambda(title), [title])`, the shape Kotlin/JS gives a capturing lambda.
        assertTrue(Regex("""useMemoRaw\(function \(\) \{\s+return title \+ '!';\s+\}, \[title]\)""") in mjs, "Expected inline callback in:\n$mjs")
    }

    @Test
    fun `keeps a useMemo that spreads its dependencies`() {
        val mjs = compile(
            """
            fun <T> useList(vararg elements: T): List<T> = useMemo(*elements) { elements.asList() }

            suspend fun useSuspended(): Int = 42

            class Holder {
                fun useMember(): Int = 1
            }
            """,
        )

        assertTrue("return useMemo(tmp, function () {" in mjs, "Expected spread useMemo left as is in:\n$mjs")
        assertFalse("'use memo';" in mjs, "Expected no 'use memo' in spread/suspend/member hooks in:\n$mjs")
    }

    @Test
    fun `leaves hooks and components that start with use no memo uncompiled`() {
        val mjs = compile(
            """
            interface ItemProps : FragmentProps {
                var title: String
            }

            val span: ElementType<ItemProps> = "span".unsafeCast<ElementType<ItemProps>>()

            fun useTitle(props: ItemProps): String {
                js("\"use no memo\"")
                return useMemo(props.title) { props.title }
            }

            val Title: FC<ItemProps> = FC { props ->
                js("\"use no memo\"")
                span { +props.title }
            }
            """,
        )

        assertEquals(
            2,
            Regex("'use memo';\\s*'use no memo';").findAll(mjs).count(),
            "Expected 'use no memo' right after 'use memo', where React Compiler reads both as directives, in:\n$mjs",
        )
        assertTrue("function Title\$lambda(props)" in mjs, "Expected the opted-out component still lowered in:\n$mjs")
    }

    @Test
    fun `hoists non-trivial useCallback and useMemo dependencies before the raw hook call`() {
        val mjs = compile(
            """
            interface ItemProps : Props {
                var title: String
                var step: Int
            }

            fun useDerived(props: ItemProps): () -> Int {
                val len = useMemo(props.title.trim()) { props.title.length }
                return useCallback(len, props.step + 1) { len + props.step }
            }
            """,
        )

        assertTrue("'use memo';" in mjs, "Expected 'use memo' in:\n$mjs")
        assertTrue(Regex("""var (dep\w*) = toString\(trim\([^\n]+\)\);\s+var len = useMemoRaw\(function \(\) \{[^}]+\}, \[\1\]\);""") in mjs, "Expected hoisted useMemo dependency in:\n$mjs")
        assertTrue(Regex("""var (dep\w*) = props\.get_step_[\w$]+\(\) \+ 1 \| 0;\s+return useCallbackRaw\(function \(\) \{[^}]+\}, \[len, \1\]\);""") in mjs, "Expected hoisted useCallback dependency in:\n$mjs")
    }
}
