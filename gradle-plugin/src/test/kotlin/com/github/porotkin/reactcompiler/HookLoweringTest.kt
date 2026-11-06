package com.github.porotkin.reactcompiler

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
            """,
        )

        assertTrue("return useMemo(tmp, useList\$lambda(elements));" in mjs, "Expected spread useMemo left as is in:\n$mjs")
    }
}
