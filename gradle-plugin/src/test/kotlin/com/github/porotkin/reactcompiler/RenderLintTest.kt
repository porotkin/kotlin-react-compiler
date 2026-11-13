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
}
