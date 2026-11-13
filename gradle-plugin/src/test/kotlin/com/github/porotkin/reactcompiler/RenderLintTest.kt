package com.github.porotkin.reactcompiler

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RenderLintTest : LintTest() {
    @Test
    fun `reports refs read or written during render`() {
        val result = assertLint(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

            interface ListProps : Props {
                var count: Int
                var anchor: RefObject<String>
            }

            val Accesses: FC<ListProps> = FC { props ->
                val ref = useRef<String>()
                val width = ref.current?.length ?: 0 // ! REACT_REFS
                ref.current = "next" // ! REACT_REFS
                val anchored = props.anchor.current // ! REACT_REFS
                val memoized = useMemo(props.count) { ref.current } // ! REACT_REFS
                div {
                    +"${'$'}{ref.current} ${'$'}width ${'$'}anchored ${'$'}memoized" // ! REACT_REFS
                }
            }

            val Delegated: FC<ListProps> = FC { props ->
                var latest by useRefValue(props.count)
                latest = props.count // ! REACT_REFS
                val seen = latest // ! REACT_REFS
                useEffect { latest = 0 }
                div { +"${'$'}seen" }
            }

            fun useLength(ref: RefObject<String>): Int =
                ref.current?.length ?: 0 // ! REACT_REFS

            val Reference: FC<ListProps> = FC {
                val ref = useRef<String>()
                val getter = ref::current // ! REACT_REFS
                div { +"${'$'}getter" }
            }

            // kotlin-react's `useCallback` is inline, and still gets its callback as a value.
            inline fun <T : Function<*>> useEventCallback(callback: T): T =
                useCallback(callback = callback)

            inline fun <T> useComputed(compute: () -> T): T =
                compute()

            val Allowed: FC<ListProps> = FC { props ->
                val ref = useRef<String>()
                if (ref.current == null) {
                    ref.current = "initial"
                }
                useEffect(props.count) { ref.current = "effect" }
                val onClick = { ref.current = "click" }
                val getElement = { ref.current }
                val onEvent = useEventCallback { ref.current = "event" }
                val passed = useLength(ref)
                div { +"${'$'}onClick ${'$'}getElement ${'$'}onEvent ${'$'}passed" }
            }

            val Inlined: FC<ListProps> = FC {
                val ref = useRef<String>()
                val computed = useComputed { ref.current } // ! REACT_REFS
                div { +"${'$'}computed" }
            }

            val OptedOut: FC<ListProps> = FC {
                js("\"use no memo\"")
                val ref = useRef<String>()
                val value = ref.current
                div { +"${'$'}value" }
            }

            @Suppress("REACT_REFS")
            val Suppressed: FC<ListProps> = FC {
                val ref = useRef<String>()
                val value = ref.current
                div { +"${'$'}value" }
            }
            """,
        )

        assertTrue("Ref value `current` is read during render." in result.output, result.output)
        assertTrue("Ref value `latest` is written during render." in result.output, result.output)
    }

    @Test
    fun `reports props and state modified, and hook arguments, hook results and passed values modified during render`() {
        assertLint(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

            class Settings(var enabled: Boolean = false, val tags: MutableList<String> = mutableListOf())

            class Control {
                var channel: String? = null
            }

            interface FormProps : Props {
                var title: String
                var settings: Settings
                var items: MutableList<String>
            }

            fun useControl(): Control = useMemo { Control() }

            fun useSettings(): Pair<Settings, Int> = Settings() to 0

            val MutatesProps: FC<FormProps> = FC { props ->
                props.title = "changed" // ! REACT_IMMUTABILITY
                props.settings.enabled = true // ! REACT_IMMUTABILITY
                props.items.add("item") // ! REACT_IMMUTABILITY
                val settings = props.settings
                settings.enabled = false // ! REACT_IMMUTABILITY
                val items = props.items
                items += "other" // ! REACT_IMMUTABILITY
                div { +props.title }
            }

            val MutatesHookResults: FC<FormProps> = FC { props ->
                val control = useControl()
                control.channel = props.title // ! REACT_IMMUTABILITY
                val configured = useControl().apply {
                    channel = props.title // ! REACT_IMMUTABILITY
                }
                val (settings, count) = useSettings()
                settings.enabled = count > 0 // ! REACT_IMMUTABILITY
                useControl().channel = "direct" // ! REACT_IMMUTABILITY
                div { +"${'$'}{control.channel} ${'$'}{configured.channel}" }
            }

            fun useNormalized(settings: Settings): Settings {
                settings.enabled = true // ! REACT_IMMUTABILITY
                return settings
            }

            val Styled: FC<FormProps> = FC { props ->
                div { +props.title }
            }

            val MutatesSnapshots: FC<FormProps> = FC { props ->
                val (items, setItems) = useState(mutableListOf<String>())
                var selected by useState(Settings())
                val onAdd = {
                    items.add("item") // ! REACT_IMMUTABILITY
                    setItems(items)
                    selected.enabled = true // ! REACT_IMMUTABILITY
                }
                useEffect(props.title) {
                    props.settings.enabled = false // ! REACT_IMMUTABILITY
                }
                div { +"${'$'}onAdd" }
            }

            val MutatesPassedValues: FC<FormProps> = FC { props ->
                val styles = Settings()
                styles.enabled = true
                Styled { settings = styles }
                styles.enabled = false // ! REACT_IMMUTABILITY
                val options = Settings()
                val normalized = useNormalized(options) // the hook above
                options.tags.add("late") // ! REACT_IMMUTABILITY
                div { +"${'$'}normalized ${'$'}{props.title}" }
            }

            val Allowed: FC<FormProps> = FC { props ->
                val control = useControl()
                val local = Settings()
                local.enabled = true
                local.tags.add("tag")
                val copy = props.items.toMutableList()
                copy.add("item")
                useEffect(control) { control.channel = props.title }
                val onClick = { control.channel = null }
                div {
                    key = props.title
                    +"${'$'}local ${'$'}copy ${'$'}onClick"
                }
            }
            """,
        )
    }
}
