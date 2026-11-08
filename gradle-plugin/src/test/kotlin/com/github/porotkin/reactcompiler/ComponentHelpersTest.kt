package com.github.porotkin.reactcompiler

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComponentHelpersTest : PluginTest() {
    @Test
    fun `lowers a generic private helper with a root early return`() {
        val mjs = compile(
            """
            interface ItemProps : FragmentProps {
                var active: Boolean
                var title: String
            }

            val span: ElementType<ItemProps> = "span".unsafeCast<ElementType<ItemProps>>()

            val GenericView: FC<ItemProps> = FC { props ->
                GenericView(props)
            }

            private fun <T : ItemProps> ChildrenBuilder.GenericView(props: T) {
                val title = useMemo(props.title.length) { props.title }

                if (!props.active) {
                    span()
                    return
                }

                span { +title }
            }
            """,
        )

        assertTrue("function useGenericView(props)" in mjs, "Expected generic helper lowered without builder receiver in:\n$mjs")
        assertTrue("function GenericView\$lambda(props)" in mjs, "Expected GenericView component lowered in:\n$mjs")
    }

    @Test
    fun `names lowered helpers that call hooks as hooks`() {
        val mjs = compile(
            """
            interface ItemProps : FragmentProps {
                var title: String
            }

            val span: ElementType<ItemProps> = "span".unsafeCast<ElementType<ItemProps>>()

            val View: FC<ItemProps> = FC { props ->
                outer(props)
                useHeader(props)
                Plain(props)
            }

            private fun ChildrenBuilder.useHeader(props: ItemProps) {
                val title = useMemo(props.title.length) { props.title }
                span { +title }
            }

            private fun ChildrenBuilder.outer(props: ItemProps) {
                Inner(props)
            }

            private fun ChildrenBuilder.Inner(props: ItemProps) {
                val title = useMemo(props.title.length) { props.title }
                span { +title }
            }

            private fun ChildrenBuilder.Plain(props: ItemProps) {
                span { +props.title }
            }
            """,
        )

        assertTrue("function useInner(props)" in mjs, "Expected helper calling a hook named as a hook in:\n$mjs")
        assertTrue("function useOuter(props)" in mjs, "Expected helper reaching a hook through another helper named as a hook in:\n$mjs")
        assertTrue("useInner(props)" in mjs.substringAfter("function useOuter(props)"), "Expected useOuter to call useInner in:\n$mjs")
        assertTrue("useOuter(props)" in mjs.substringAfter("function View\$lambda(props)"), "Expected View to call useOuter in:\n$mjs")
        assertTrue("function Plain(props)" in mjs, "Expected helper without hooks to keep its name in:\n$mjs")
        assertTrue("function useHeader(props)" in mjs, "Expected helper already named as a hook to keep its name in:\n$mjs")
    }

    @Test
    fun `clones a private ChildrenBuilder helper when shared with an unlowered caller`() {
        val mjs = compile(
            """
            interface ItemProps : FragmentProps {
                var title: String
            }

            val span: ElementType<ItemProps> = "span".unsafeCast<ElementType<ItemProps>>()

            fun escape(builder: ChildrenBuilder) {}

            val LoweredCaller: FC<ItemProps> = FC { props ->
                Shared(props)
            }

            val UnloweredCaller: FC<ItemProps> = FC { props ->
                escape(this)
                Shared(props)
            }

            private fun ChildrenBuilder.Shared(props: ItemProps) {
                span { +props.title }
            }
            """,
        )

        assertFalse("throwIrLinkageError" in mjs, "Expected no linkage error when helper is shared with unlowered caller in:\n$mjs")
        assertTrue(Regex("""function Shared\([\w$]+, props\)""") in mjs, "Expected original 2-arg helper kept for unlowered caller in:\n$mjs")
        assertTrue("function Shared\$lowered(props)" in mjs, "Expected cloned 1-arg helper emitted for lowered caller in:\n$mjs")
        assertTrue("Shared\$lowered(props)" in mjs, "Expected LoweredCaller to call Shared\$lowered in:\n$mjs")
        assertTrue("Shared(\$this\$FC, props)" in mjs, "Expected UnloweredCaller to call original Shared in:\n$mjs")
    }

    @Test
    fun `executes providers, element props and cloned helpers in Node`() {
        TestProject(dir).apply {
            module(
                "app",
                """
                package app

                import react.*

                interface StateProps : PropsWithChildren {
                    var value: Int
                    var setter: (Int) -> Unit
                }

                val span: ElementType<FragmentProps> = "span".unsafeCast<ElementType<FragmentProps>>()
                val ThemeContext: Provider<String> = "ThemeContext".unsafeCast<Provider<String>>()
                val Counter: ElementType<StateProps> = "Counter".unsafeCast<ElementType<StateProps>>()

                var escaped = false

                fun escape(builder: ChildrenBuilder) {
                    escaped = true
                }

                private fun ChildrenBuilder.Shared(props: Props) {
                    span { +"shared" }
                }

                val UsesShared: FC<Props> = FC { props -> Shared(props) }

                val Unlowered: FC<Props> = FC { props ->
                    escape(this)
                    if (props.asDynamic().never == true) Shared(props)
                }

                val WithProvider: FC<Props> = FC { _ ->
                    ThemeContext("dark") {
                        Counter {
                            value = 5
                            setter = {}
                            span { +"x" }
                        }
                    }
                }

                @JsExport
                fun renderProvider(): Any? =
                    WithProvider.asDynamic()(js("({})"))

                @JsExport
                fun renderShared(): Any? =
                    UsesShared.asDynamic()(js("({})"))

                @JsExport
                fun callUnlowered(): Boolean {
                    Unlowered.asDynamic()(js("({})"))
                    return escaped
                }
                """,
            )
        }.run(":app:compileDevelopmentExecutableKotlinJs")
        // JSON.stringify drops the function-valued setter prop, so the expected trees omit it.
        val output = runNode(
            "app",
            """
            const tree = { provider: m.renderProvider(), shared: m.renderShared() };
            console.log(JSON.stringify({ tree, escaped: m.callUnlowered() }));
            """.trimIndent(),
        )
        assertTrue(""""provider":{"type":"ThemeContext","props":{"value":"dark","children":{"type":"Counter","props":{"value":5,"children":{"type":"span","props":{"children":"x"}""" in output, "Unexpected provider tree in:\n$output")
        assertTrue(""""shared":{"type":"span","props":{"children":"shared"}""" in output, "Unexpected shared tree in:\n$output")
        assertTrue(""""escaped":true""" in output, "Expected unlowered caller to run with a builder in:\n$output")
    }
}
