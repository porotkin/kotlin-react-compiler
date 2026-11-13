package com.github.porotkin.reactcompiler

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RulesOfHooksLintTest : LintTest() {
    @Test
    fun `accepts hooks at the top level of components, hooks, and the blocks that run as part of them`() {
        assertLint(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()
            val ThemeContext: Context<String> = js("null").unsafeCast<Context<String>>()

            fun useValue(): Int = 1

            fun useDouble(value: Int): Int {
                val doubled = useMemo(value) { value * 2 }
                return doubled + useValue()
            }

            interface TitleProps : Props {
                var title: String?
            }

            val Title: FC<TitleProps> = FC { props ->
                val (count, setCount) = useState(0)
                val a = useValue()
                val b = run { useValue() }
                val c = useValue().let { it + useValue() }
                val d = with(props) { useDouble(a) }
                val title = props.title ?: error("No title")
                val e = useValue()

                val onClick = { setCount(a + b + c + d + e) }
                useEffect(count) {
                    println(count)
                }

                div {
                    val inElement = useValue()
                    +"${'$'}title ${'$'}inElement ${'$'}onClick"
                }
            }

            private fun ChildrenBuilder.Header(props: TitleProps) {
                val value = useValue()
                div { +"${'$'}{props.title} ${'$'}value" }
            }

            val WithHeader: FC<TitleProps> = FC { props ->
                Header(props)
            }

            private fun <P : TitleProps> GenericTitle(props: P): Int =
                useValue()

            val Generic: FC<TitleProps> = FC { props ->
                val value = GenericTitle(props)
                div { +"${'$'}value" }
            }

            val Conditional: FC<TitleProps> = FC { props ->
                val theme = if (props.title != null) use(ThemeContext) else "none"
                div { +theme }
            }
            """,
            fileAnnotations = """@file:Suppress("REACT_ONLY_EXPORT_COMPONENTS")""",
        )
    }

    @Test
    fun `reports hooks called conditionally, repeatedly, or after an early return`() {
        val result = assertLint(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

            fun useValue(): Int = 1
            fun useFlag(): Boolean = true
            fun useNullable(): String? = null

            interface ModeProps : Props {
                var forExcept: Boolean
                var items: List<String>
            }

            val Branches: FC<ModeProps> = FC { props ->
                val enabled = if (props.forExcept) useValue() else useValue() // ! REACT_RULES_OF_HOOKS REACT_RULES_OF_HOOKS
                if (useFlag()) {
                    useEffect(enabled) {} // ! REACT_RULES_OF_HOOKS
                }
                val both = useFlag() && useFlag() // ! REACT_RULES_OF_HOOKS
                val fallback = useNullable() ?: useNullable() // ! REACT_RULES_OF_HOOKS
                val length = useNullable()?.let { useValue() } // ! REACT_RULES_OF_HOOKS
                when (enabled) {
                    1 -> useValue() // ! REACT_RULES_OF_HOOKS
                    else -> {}
                }
                div { +"${'$'}both ${'$'}fallback ${'$'}length" }
            }

            val Loops: FC<ModeProps> = FC { props ->
                for (item in props.items) {
                    useEffect(item) {} // ! REACT_RULES_OF_HOOKS
                }
                while (useFlag()) { // ! REACT_RULES_OF_HOOKS
                    break
                }
                props.items.forEach { useEffect(it) {} } // ! REACT_RULES_OF_HOOKS
                val values = props.items.map { useValue() } // ! REACT_RULES_OF_HOOKS
                div { +"${'$'}values" }
            }

            val EarlyReturn: FC<ModeProps> = FC { props ->
                val first = useValue()
                if (props.forExcept) return@FC
                val second = useValue() // ! REACT_RULES_OF_HOOKS
                div { +"${'$'}first ${'$'}second" }
            }

            val ElvisReturn: FC<ModeProps> = FC {
                val item = useNullable() ?: return@FC
                val value = useValue() // ! REACT_RULES_OF_HOOKS
                div { +"${'$'}item ${'$'}value" }
            }

            fun useEarly(flag: Boolean): Int {
                if (flag) return 0
                return useValue() // ! REACT_RULES_OF_HOOKS
            }

            val Guarded: FC<ModeProps> = FC {
                val value = try {
                    useValue() // ! REACT_RULES_OF_HOOKS
                } catch (e: Throwable) {
                    0
                }
                div {
                    if (value > 0) {
                        val nested = useValue() // ! REACT_RULES_OF_HOOKS
                        +"${'$'}nested"
                    }
                }
            }
            """,
        )

        assertTrue("Hook `useValue` is called conditionally." in result.output, result.output)
        assertTrue("Hook `useEffect` is called in a loop." in result.output, result.output)
        assertTrue("Hook `useValue` is called after an early return." in result.output, result.output)
        assertTrue("Hook `useValue` is called inside try/catch/finally." in result.output, result.output)
        assertTrue("is called in a lambda that may run more than once or not at all." in result.output, result.output)
    }

    @Test
    fun `reports hooks called in nested functions and outside components and hooks`() {
        val result = assertLint(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

            fun useValue(): Int = 1
            fun useItems(): List<String> = emptyList()

            fun render(block: () -> Int): Int = block()

            val Callbacks: FC<Props> = FC {
                val onClick = { useValue() } // ! REACT_RULES_OF_HOOKS
                val memoized = useMemo { useValue() } // ! REACT_RULES_OF_HOOKS
                val callback = useCallback { useItems().firstOrNull() } // ! REACT_RULES_OF_HOOKS
                useEffect {
                    useValue() // ! REACT_RULES_OF_HOOKS
                }
                fun local(): Int = useValue() // ! REACT_RULES_OF_HOOKS
                div { +"${'$'}onClick ${'$'}memoized ${'$'}callback ${'$'}{local()}" }
            }

            fun useLogItemCallback(id: String): () -> String? =
                useCallback(id) {
                    useItems().firstOrNull { it == id } // ! REACT_RULES_OF_HOOKS
                }

            fun getValue(): Int =
                useValue() // ! REACT_RULES_OF_HOOKS

            suspend fun useSuspended(): Int =
                useValue() // ! REACT_RULES_OF_HOOKS

            val topLevel: Int = useValue() // ! REACT_RULES_OF_HOOKS

            class Store {
                val initial = useValue() // ! REACT_RULES_OF_HOOKS

                fun load(): Int = useValue() // ! REACT_RULES_OF_HOOKS
            }

            // A lambda outside components and hooks may well be rendered: `renderHook { useValue() }` in a test.
            fun rendersHook(): Int =
                render { useValue() }
            """,
        )

        assertTrue("Hook `useValue` is called in a nested function." in result.output, result.output)
        assertTrue("Hook `useValue` is called in `getValue`, which is neither a component nor a hook." in result.output, result.output)
        assertTrue("Hook `useValue` is called outside a component or hook." in result.output, result.output)
        assertTrue("Hook `useValue` is called in a suspend function." in result.output, result.output)
    }
}
