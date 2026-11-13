package com.github.porotkin.reactcompiler

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HookUsageLintTest : LintTest() {
    @Test
    fun `warns about missing and unnecessary dependencies, unless suppressed`() {
        val result = assertLint(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

            interface ItemProps : Props {
                var title: String
                var items: List<String>
                var filter: (String) -> Boolean
            }

            fun useStore(): List<String> = emptyList()

            val Complete: FC<ItemProps> = FC { props ->
                val ref = useRef<String>()
                val (count, setCount) = useState(0)
                val limit = 10
                val store = useStore()
                val title = props.title

                // Every value read is a dependency, as itself or through what it is read from.
                val filtered = useMemo(props.items, props.filter) { props.items.filter(props.filter) }
                val byProps = useMemo(props) { props.items.size }
                val label = useMemo(title, count) { "${'$'}title ${'$'}count" }
                // A ref, a state setter and a constant never change.
                val onClick = useCallback(count) {
                    ref.current = "clicked"
                    setCount(count + limit)
                }
                // A dependency that is an expression covers the values it mentions.
                val size = useMemo(store.size > 0) { store.size }
                // Without dependencies an effect runs after every render.
                useEffect { println(title) }
                useEffect(title, store) { println("${'$'}title ${'$'}store") }
                div { +"${'$'}filtered ${'$'}byProps ${'$'}label ${'$'}onClick ${'$'}size" }
            }

            val Incomplete: FC<ItemProps> = FC { props ->
                val (count, setCount) = useState(0)
                val store = useStore()
                var name by useState("")

                val filtered = useMemo(props.items) { props.items.filter(props.filter) } // ? REACT_EXHAUSTIVE_DEPS
                val label = useMemo { "${'$'}{props.title} ${'$'}count" } // ? REACT_EXHAUSTIVE_DEPS
                val onClick = useCallback(store) { setCount(count + 1) } // ? REACT_EXHAUSTIVE_DEPS
                val onRename = useCallback { name = "renamed" }
                useEffect(count) { // ? REACT_EXHAUSTIVE_DEPS
                    println("${'$'}name ${'$'}store")
                    println("${'$'}name again")
                }
                useEffectOnce { println(count) } // ? REACT_EXHAUSTIVE_DEPS
                div { +"${'$'}filtered ${'$'}label ${'$'}onClick ${'$'}onRename" }
            }

            fun useSize(vararg items: String): Int =
                useMemo(*items) { items.size } // ? REACT_EXHAUSTIVE_DEPS

            // A hook that passes its own dependencies on is checked where it is called.
            fun useLogged(vararg dependencies: Any?, log: () -> Unit) {
                useEffect(dependencies = dependencies) { log() }
            }

            fun useAnchor(anchor: RefObject<String>, title: String) {
                useLogged(title) { println(anchor.current) } // ? REACT_EXHAUSTIVE_DEPS
            }

            val Suppressed: FC<ItemProps> = FC { props ->
                val (count) = useState(0)
                val once = useMemo @Suppress("REACT_EXHAUSTIVE_DEPS") { props.title }

                @Suppress("REACT_EXHAUSTIVE_DEPS")
                val label = useMemo { "${'$'}count" }
                useEffect(count) @Suppress("REACT_EXHAUSTIVE_DEPS") { println(props.title) }
                div { +"${'$'}once ${'$'}label" }
            }
            """,
        )

        assertTrue("Hook `useMemo` has a missing dependency: `props.filter`." in result.output, result.output)
        assertTrue("Hook `useMemo` has missing dependencies: `props.title`, `count`." in result.output, result.output)
        assertTrue("Hook `useCallback` has a missing dependency: `count`." in result.output, result.output)
        assertTrue("It also has an unnecessary dependency: `store`. Its callback does not read it" in result.output, result.output)
        assertTrue("Hook `useEffect` has missing dependencies: `name`, `store`. Its callback reads them, so the hook must run again when they change" in result.output, result.output)
        assertTrue("Hook `useMemo` spreads an array into its dependencies" in result.output, result.output)
        assertTrue("Hook `useLogged` has a missing dependency: `anchor`." in result.output, result.output)
    }

    @Test
    fun `reports effects that set state at once, useMemo without a value, and elements in try`() {
        assertLint(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

            interface TitleProps : Props {
                var title: String
            }

            val Title: FC<TitleProps> = FC { props ->
                div { +props.title }
            }

            suspend fun load(): String = "loaded"

            val Effects: FC<TitleProps> = FC { props ->
                val ref = useRef<String>()
                val (title, setTitle) = useState("")
                var loading by useState(false)

                useEffect(props.title) {
                    setTitle(props.title) // ! REACT_SET_STATE_IN_EFFECT
                }
                useLayoutEffect(props.title) {
                    loading = true // ! REACT_SET_STATE_IN_EFFECT
                    val loaded = load()
                    setTitle(loaded)
                    loading = false
                }
                // The value of a ref is what an effect is there to read.
                useEffect(props.title) {
                    val measured = ref.current
                    setTitle(measured ?: "")
                }
                // A callback the effect registers runs later.
                useEffect(props.title) {
                    val onChange = { value: String -> setTitle(value) }
                    onChange(props.title)
                }
                div { +"${'$'}title ${'$'}loading" }
            }

            val Memoizes: FC<TitleProps> = FC { props ->
                val nothing = useMemo(props.title) { println(props.title) } // ! REACT_USE_MEMO
                useMemo(props.title) { props.title.length } // ! REACT_USE_MEMO
                div { +"${'$'}nothing" }
            }

            val Guarded: FC<TitleProps> = FC { props ->
                try {
                    Title { title = props.title } // ! REACT_ERROR_BOUNDARIES
                } catch (e: Throwable) {
                    div { +"failed" }
                }
            }
            """,
        )
    }
}
