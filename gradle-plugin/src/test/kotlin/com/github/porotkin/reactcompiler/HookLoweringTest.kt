package com.github.porotkin.reactcompiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HookLoweringTest : PluginTest() {
    @Test
    fun `passes an empty dependency array and inline callback to a contextless useMemo`() {
        val mjs = compile(
            """
            fun useHeader(): String = useMemo { "Results" }
            """,
        )

        assertTrue(
            Regex("""useMemoRaw\(function \(\) \{\s+return 'Results';\s+\}, \[\]\)""") in mjs,
            "Expected inline callback and empty dependency array in:\n$mjs"
        )
        assertFalse("useHeader\$lambda" in mjs, "Expected contextless hook callback not lifted in:\n$mjs")
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
                @Suppress("REACT_HOOKS_AS_VALUES")
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
        assertTrue(isLowered(mjs, "Title\$lambda"), "Expected the opted-out component still lowered in:\n$mjs")
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

    @Test
    fun `lowers ref and state local delegates without reflection metadata`() {
        val mjs = compile(
            """
            import react.use.useLatest

            value class Tag(val raw: String)

            fun useTrackedState(initial: Int, latestLabel: String): () -> String {
                val readOnly by useState(initial)
                var count by useState(initial)
                var dragging by useRefValue(false)
                var tag by useRefValue(Tag("init"))
                val latest by useLatest(latestLabel)
                return useCallback(readOnly, count) {
                    if (!dragging) {
                        dragging = true
                        tag = Tag(latest)
                        count = count + 1
                    }
                    "${'$'}readOnly:${'$'}count:${'$'}{tag.raw}:${'$'}latest"
                }
            }
            """,
        )

        assertFalse("getLocalDelegateReference" in mjs, "Expected no local delegate KProperty metadata in:\n$mjs")
        assertFalse("KMutableProperty0" in mjs, "Expected no KMutableProperty0 import in:\n$mjs")
        assertFalse("KProperty0" in mjs, "Expected no KProperty0 import in:\n$mjs")
        assertTrue(".current" in mjs, "Expected direct .current access for ref delegates in:\n$mjs")
        assertTrue(
            "component1" in mjs && "component2" in mjs,
            "Expected component1/component2 destructuring for state delegates in:\n$mjs"
        )
    }

    @Test
    fun `eta-expands callable references passed to hooks while preserving KFunction metadata on custom hooks`() {
        val mjs = compile(
            """
            import kotlin.reflect.KFunction1

            class Formatter(val prefix: String) {
                fun format(value: Int): String = "${'$'}prefix:${'$'}value"
            }

            fun defaultHeader(): String = "Header"

            fun resetCounter(step: Int = 1): Int = step

            fun <T> useNamedFactory(factory: KFunction1<String, T>): String = factory.name

            fun usePlainMapper(mapper: (Int) -> String): String = mapper(1)

            fun usePlainAction(action: () -> Unit) {
                action()
            }

            fun useFormatted(formatter: Formatter): Pair<String, (Int) -> String> {
                val header = useMemo(callback = ::defaultHeader)
                val format = useCallback(formatter, callback = formatter::format)
                usePlainAction(::resetCounter)
                val mapped = usePlainMapper(formatter::format)
                val factoryName = useNamedFactory(::Formatter)
                return "${'$'}header:${'$'}mapped:${'$'}factoryName" to format
            }
            """,
        )

        assertFalse("defaultHeader\$ref" in mjs, "Expected ::defaultHeader eta-expanded inline in:\n$mjs")
        assertFalse("format\$ref" in mjs, "Expected bound formatter::format eta-expanded inline in:\n$mjs")
        assertFalse("resetCounter\$ref" in mjs, "Expected adapted ::resetCounter eta-expanded inline in:\n$mjs")
        assertTrue(
            "Formatter\$_init_\$ref" in mjs,
            "Expected KFunction1 custom hook argument to keep \$ref metadata in:\n$mjs"
        )
        assertTrue(
            Regex("""useMemoRaw\(function \(\) \{\s+return defaultHeader\(\);\s+\}, \[\]\)""") in mjs,
            "Expected inline defaultHeader call in:\n$mjs"
        )
        assertTrue(
            Regex("""useCallbackRaw\(function \([\w$]+\) \{\s+return formatter\.format_[\w$]+\([\w$]+\);\s+\}, \[formatter\]\)""") in mjs,
            "Expected inline bound formatter.format call in:\n$mjs"
        )
    }

    @Test
    fun `creates suspend lambdas passed to custom hooks when the hook calls them`() {
        val mjs = compile(
            """
            fun useAsyncEffect(key: Any?, block: suspend (String) -> Unit) {
                useEffect(key) { block("x") }
            }

            fun usePlain(block: suspend () -> Unit): Any = block

            fun useLoader(title: String) {
                val ref = useRef<String>(null)
                useAsyncEffect(title) { ref.current = it + title }
                val stored: suspend () -> Unit = { ref.current = title }
                usePlain(stored)
            }
            """,
        )

        assertTrue(
            Regex("""useAsyncEffect\(title, function \(p0, completion\) \{\s+return useLoader\$[\w$]+\(ref, title, null\)\(p0, completion\);\s+\}\);""") in mjs,
            "Expected the suspend lambda created inside a plain function taking its parameter and the continuation in:\n$mjs"
        )
        assertTrue(Regex("""usePlain\(stored\)""") in mjs, "Expected a stored suspend lambda passed as it is in:\n$mjs")
        assertTrue(
            Regex("""useEffect\(function \(\) \{\s+return createCleanupCallback\(useAsyncEffect\$[\w$]+\(block, null\)\)\(\);""") in mjs,
            "Expected kotlin-react's useEffect lowered to React's, not wrapped, in:\n$mjs"
        )
    }

    @Test
    fun `lowers synchronous and suspending effects to direct React effect calls without Unit returns`() {
        val mjs = compile(
            """
            var sideEffectLog = ""

            suspend fun suspendStep() {}

            fun makeEffect(title: String): suspend () -> Unit = { sideEffectLog += title }

            @Suppress("REACT_GLOBALS")
            fun useEffects(title: String) {
                val ref = useRef<String>(null)
                useEffect {
                    sideEffectLog += "every;"
                }
                useEffectOnce {
                    if (sideEffectLog.isEmpty()) return@useEffectOnce
                    sideEffectLog += "once;"
                }
                useEffect(title.trim()) {
                    ref.current = title
                }
                useLayoutEffect(title) {
                    suspendStep()
                    ref.current = title
                }
                useEffect(title, effect = makeEffect(title))
            }
            """,
        )

        val useEffectsBody = mjs.substringAfter("function useEffects(").substringBefore("\n}")
        assertTrue(
            Regex("""import \{\s*useEffect as useEffect,\s*useLayoutEffect as useLayoutEffect,?\s*\} from 'react'""") in mjs,
            "Expected direct React effect imports in:\n$mjs"
        )
        assertFalse(
            "Unit_getInstance" in useEffectsBody,
            "Expected no Unit_getInstance() return in useEffects body:\n$useEffectsBody"
        )
        assertTrue(
            Regex("""useEffect\(function \(\) \{[^}]*return undefined;\s*\}\);""") in mjs,
            "Expected 1-arg synchronous useEffect returning undefined in:\n$mjs"
        )
        assertTrue(
            Regex("""useEffect\(function \(\) \{[\s\S]*?once;[\s\S]*?return undefined;\s*\}, \[\]\);""") in mjs,
            "Expected useEffectOnce lowered with [] and undefined return in:\n$mjs"
        )
        assertTrue(
            Regex("""var (dep\w*) = toString\(trim\([^\n]+\)\);\s+useEffect\(function \(\) \{\s+mutableValue\w*\(ref\)\.current = title;\s+return undefined;\s+\}, \[\1\]\);""") in mjs,
            "Expected hoisted dep and the ref written through mutableValue in synchronous useEffect in:\n$mjs"
        )
        assertTrue(
            Regex("""useLayoutEffect\(function \(\) \{\s+return createCleanupCallback\([^\n]+\)\(\);\s+\}, \[title\]\);""") in mjs,
            "Expected suspending effect wrapped via createCleanupCallback inside inline callback in:\n$mjs"
        )
        assertTrue(
            Regex("""var (effect\w*) = makeEffect\(title\);\s+useEffect\(function \(\) \{\s+return createCleanupCallback\(\1\)\(\);\s+\}, \[title\]\);""") in mjs,
            "Expected a non-lambda effect evaluated during render, before the call, in:\n$mjs"
        )
    }
}
