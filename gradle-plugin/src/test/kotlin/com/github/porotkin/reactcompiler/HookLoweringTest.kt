package com.github.porotkin.reactcompiler

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

        assertTrue("return useMemo(tmp, useList\$lambda(elements));" in mjs, "Expected spread useMemo left as is in:\n$mjs")
        assertFalse("'use memo';" in mjs, "Expected no 'use memo' in spread/suspend/member hooks in:\n$mjs")
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
        assertTrue(Regex("""var (dep\w*) = toString\(trim\([^\n]+\)\);\s+var len = useMemoRaw\([^\n]+, \[\1\]\);""") in mjs, "Expected hoisted useMemo dependency in:\n$mjs")
        assertTrue(Regex("""var (dep\w*) = props\.get_step_[\w$]+\(\) \+ 1 \| 0;\s+return useCallbackRaw\([^\n]+, \[len, \1\]\);""") in mjs, "Expected hoisted useCallback dependency in:\n$mjs")
    }
}
