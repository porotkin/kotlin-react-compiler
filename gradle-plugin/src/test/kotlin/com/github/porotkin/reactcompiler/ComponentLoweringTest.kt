package com.github.porotkin.reactcompiler

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComponentLoweringTest : PluginTest() {
    @Test
    fun `wraps several root elements in a Fragment`() {
        val mjs = compile(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

            val Pair: FC<Props> = FC { _ ->
                div { }
                div { }
            }
            """,
        )

        assertTrue("function Pair\$lambda(_unused_var__" in mjs, "Expected lowered component in:\n$mjs")
        assertTrue("throwIrLinkageError" !in mjs, "Expected a linkable Fragment jsx call in:\n$mjs")
        assertTrue(Regex("""jsx\(get_Fragment\(\), \w+, null\)""") in mjs, "Expected keyless Fragment jsx call in:\n$mjs")
    }

    @Test
    fun `keeps wrappers around a component in a property initializer`() {
        val mjs = compile(
            """
            val Plain: FC<Props> = memo(FC { _ -> })
            """,
        )

        assertTrue("Plain = memo(Plain\$lambda);" in mjs, "Expected memo call kept in:\n$mjs")
    }

    @Test
    fun `reads statements on the attrs object in an element body`() {
        val mjs = compile(
            """
            interface ItemProps : FragmentProps {
                var title: String
                var active: Boolean
            }

            val div: ElementType<ItemProps> = "div".unsafeCast<ElementType<ItemProps>>()

            val Styled: FC<ItemProps> = FC { props ->
                div {
                    +props
                    css {
                        title = "styled"
                    }
                    if (props.active) {
                        active = true
                    }
                    +"text"
                }
            }
            """,
        )

        assertTrue("function Styled\$lambda(props)" in mjs, "Expected Styled lowered in:\n$mjs")
        assertTrue(Regex("""attrs\.set_active_[\w$]+\(true\);""") in mjs, "Expected conditional prop set on attrs in:\n$mjs")
    }

    @Test
    fun `pushes loop children onto arrays and passes an explicit key to jsx`() {
        val mjs = compile(
            """
            interface ItemProps : FragmentProps {
                var items: List<String>
            }

            val div: ElementType<ItemProps> = "div".unsafeCast<ElementType<ItemProps>>()
            val span: ElementType<ItemProps> = "span".unsafeCast<ElementType<ItemProps>>()

            val Lists: FC<ItemProps> = FC { props ->
                div {
                    for (entry in props.items) {
                        span {
                            key = entry
                            +entry
                        }
                    }
                    repeat(props.items.size) { index ->
                        span { +index.toString() }
                    }
                }
            }
            """,
        )

        val lists = Regex("""(list\w*)\.push\(""").findAll(mjs).map { it.groupValues[1] }.toSet()
        assertTrue(lists.size == 2, "Expected for and repeat loop children pushed onto two arrays in:\n$mjs")
        assertTrue(Regex("""jsx\(type\w*, attrs\w*, entry\)""") in mjs, "Expected explicit key passed as 3rd argument to jsx in:\n$mjs")
    }

    @Test
    fun `lowers child(type, props) and an early return in a tail branch`() {
        val mjs = compile(
            """
            interface ItemProps : FragmentProps {
                var active: Boolean
                var opened: Boolean
            }

            val div: ElementType<ItemProps> = "div".unsafeCast<ElementType<ItemProps>>()
            val span: ElementType<ItemProps> = "span".unsafeCast<ElementType<ItemProps>>()

            val TailAndChildView: FC<ItemProps> = FC { props ->
                child(span, props)

                if (props.active) {
                    span()
                } else {
                    if (!props.opened) return@FC

                    div {
                        +props.active.toString()
                    }
                }
            }
            """,
        )

        assertTrue("function TailAndChildView\$lambda(props)" in mjs, "Expected TailAndChildView lowered in:\n$mjs")
        assertTrue("jsx(type, props, null)" in mjs, "Expected child(type, props) lowered to jsx in:\n$mjs")
    }

    @Test
    fun `binds a safe-call let parameter to a local and an implicit-cast element receiver to attrs`() {
        val mjs = compile(
            """
            interface ItemProps : FragmentProps {
                var title: String?
            }

            val span: ElementType<ItemProps> = "span".unsafeCast<ElementType<ItemProps>>()

            val <P : Props> ElementType<P>.truncated: ElementType<P>
                get() = this

            val LetView: FC<ItemProps> = FC { props ->
                props.title?.let { text ->
                    span.truncated {
                        title = text
                    }
                }
            }
            """,
        )

        assertTrue("function LetView\$lambda(props)" in mjs, "Expected LetView lowered in:\n$mjs")
        assertTrue("var text = tmp0_safe_receiver;" in mjs, "Expected let parameter bound to local in:\n$mjs")
        assertTrue(Regex("""attrs\.set_title_[\w$]+\(text\);""") in mjs, "Expected implicit-cast receiver remapped to attrs in:\n$mjs")
    }

    @Test
    fun `returns null from an early return after hooks`() {
        val mjs = compile(
            """
            interface SectionProps : Props {
                var visible: Boolean
                var title: String
            }

            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()
            val span: ElementType<FragmentProps> = "span".unsafeCast<ElementType<FragmentProps>>()

            val Section: FC<SectionProps> = FC { props ->
                val state = ReactModule.useState(false)

                if (!props.visible) return@FC

                div {
                    span { +props.title }
                    if (state[0] as Boolean) {
                        span { +"Details" }
                    }
                }
            }
            """,
        )

        assertTrue("return null;" in mjs, "Expected early return null in:\n$mjs")
        assertTrue("\$l\$block" !in mjs, "Expected no labeled block in:\n$mjs")
    }

    @Test
    fun `sets nested element props on its attrs object instead of a hoisted receiver lambda`() {
        val mjs = compile(
            """
            interface ButtonProps : FragmentProps {
                var type: String
            }

            val footer: ElementType<FragmentProps> = "footer".unsafeCast<ElementType<FragmentProps>>()
            val button: ElementType<ButtonProps> = "button".unsafeCast<ElementType<ButtonProps>>()

            val Banner: FC<Props> = FC { _ ->
                footer {
                    button {
                        type = "button"
                        +"Proceed"
                    }
                }
            }
            """,
        )

        assertTrue("\$this\$" !in mjs, "Expected no builder receiver lambda in:\n$mjs")
        assertTrue(Regex("""attrs\.set_type_[\w$]+\('button'\);""") in mjs, "Expected type set on button attrs in:\n$mjs")
    }

    @Test
    fun `reads a same-file component without its getter`() {
        val mjs = compile(
            """
            interface BadgeProps : Props {
                var text: String
            }

            val span: ElementType<FragmentProps> = "span".unsafeCast<ElementType<FragmentProps>>()

            val Badge: FC<BadgeProps> = FC { props ->
                span { +props.text }
            }

            val Demo: FC<BadgeProps> = FC { props ->
                Badge { text = props.text }
            }
            """,
        )

        assertTrue("var type = Badge;" in mjs, "Expected Badge read without get_Badge() in:\n$mjs")
    }

    @Test
    fun `lowers when with subject variable and char unaryPlus`() {
        val mjs = compile(
            """
            interface ItemProps : FragmentProps {
                var items: List<String>
                var delimiter: Char
            }

            val div: ElementType<ItemProps> = "div".unsafeCast<ElementType<ItemProps>>()

            val SubjectWhen: FC<ItemProps> = FC { props ->
                div {
                    when (val count = props.items.size) {
                        0 -> +props.delimiter
                        else -> +count.toString()
                    }
                }
            }
            """,
        )

        assertTrue("function SubjectWhen\$lambda(props)" in mjs, "Expected SubjectWhen lowered in:\n$mjs")
        assertTrue(Regex("""var count = props\.get_items_[\w$]+\(\)\.get_size_[\w$]+\(\);""") in mjs, "Expected subject variable emitted before branch in:\n$mjs")
        assertTrue(Regex("""var text = toString[_0-9]*\(props\.get_delimiter_[\w$]+\(\)\);""") in mjs, "Expected Char converted via toString in:\n$mjs")
    }

    @Test
    fun `keeps conditional key assignment inside a child-adding branch on attrs instead of hoisting`() {
        val mjs = compile(
            """
            interface ItemProps : FragmentProps {
                var active: Boolean
            }

            val div: ElementType<ItemProps> = "div".unsafeCast<ElementType<ItemProps>>()
            val span: ElementType<ItemProps> = "span".unsafeCast<ElementType<ItemProps>>()

            val ConditionalKeyView: FC<ItemProps> = FC { props ->
                div {
                    if (props.active) {
                        key = "active-key"
                        span { +"Active" }
                    }
                }
            }
            """,
        )

        assertTrue("function ConditionalKeyView\$lambda(props)" in mjs, "Expected ConditionalKeyView lowered in:\n$mjs")
        assertTrue(Regex("""attrs\.set_key_[\w$]+\('active-key'\);""") in mjs, "Expected conditional key set on attrs inside branch in:\n$mjs")
        assertFalse("var key = 'active-key';" in mjs, "Expected conditional key not hoisted to a var key local in:\n$mjs")
        assertTrue(Regex("""jsx\(type, attrs, '@rdk/\d+_\d+'\)""") in mjs, "Expected source key passed as 3rd argument to jsx in:\n$mjs")
    }

    @Test
    fun `does not lower a plain Function4 invocation on ChildrenBuilder as a jsx element`() {
        val mjs = compile(
            """
            val span: ElementType<FragmentProps> = "span".unsafeCast<ElementType<FragmentProps>>()
            val Fn4: ChildrenBuilder.(Int, Int, ChildrenBuilder.() -> Unit) -> Unit = { _, _, block -> block() }

            val UsesPlainFn4: FC<Props> = FC { _ ->
                Fn4(1, 2) {
                    span()
                }
            }
            """,
        )

        assertFalse("jsx(get_Fn4()" in mjs, "Expected plain Function4 not lowered to jsx in:\n$mjs")
        assertTrue("var \$this\$FC = get_Fragment();" in mjs, "Expected component using plain Function4 to bail in:\n$mjs")
        assertTrue("tmp(\$this\$FC, 1, 2, UsesPlainFn4\$lambda\$lambda);" in mjs, "Expected plain Function4 called with builder receiver in:\n$mjs")
    }

    @Test
    fun `bails when element receiver escapes into a builder lambda, PropsWithChildren helper, or generic function`() {
        val mjs = compile(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()
            val span: ElementType<FragmentProps> = "span".unsafeCast<ElementType<FragmentProps>>()

            val extra: ChildrenBuilder.() -> Unit = { span() }
            fun PropsWithChildren.helper() { span() }
            fun <T> T.genericEscape() {}

            val EscapeViaLambda: FC<Props> = FC { _ ->
                div {
                    +"first"
                    extra()
                }
            }

            val EscapeViaPropsWithChildren: FC<Props> = FC { _ ->
                div {
                    +"first"
                    helper()
                }
            }

            val EscapeViaGeneric: FC<Props> = FC { _ ->
                div {
                    +"first"
                    genericEscape()
                }
            }
            """,
        )

        assertFalse("'use memo';" in mjs, "Expected all escaping components to bail without 'use memo' in:\n$mjs")
        assertFalse("var el = jsx(" in mjs, "Expected no jsx() calls when components bail in:\n$mjs")
        assertTrue("get_extra()(\$this\$div);" in mjs, "Expected extra() called with builder receiver in:\n$mjs")
        assertTrue("helper(\$this\$div);" in mjs, "Expected helper() called with builder receiver in:\n$mjs")
        assertTrue("genericEscape(\$this\$div);" in mjs, "Expected genericEscape() called with builder receiver in:\n$mjs")
    }

    @Test
    fun `executes lowered components in Node and produces expected React element trees`() {
        compile(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()
            val span: ElementType<FragmentProps> = "span".unsafeCast<ElementType<FragmentProps>>()

            val GuardedAndLooped: FC<Props> = FC { props ->
                val mode = props.asDynamic().mode as String
                if (mode == "guard") {
                    span { +"Guarded" }
                    return@FC
                }
                div {
                    if (mode == "early") {
                        span { +"Early" }
                        return@div
                    }
                    var i = 0
                    while (i < 2) {
                        span {
                            key = "item-${'$'}i"
                            +i.toString()
                        }
                        i++
                    }
                }
            }

            @JsExport
            fun renderMode(mode: String): Any? =
                GuardedAndLooped.asDynamic()(js("({ mode: mode })"))
            """,
        )

        val output = runNode(
            "app",
            "console.log(JSON.stringify({ guardTree: m.renderMode('guard'), earlyTree: m.renderMode('early'), loopTree: m.renderMode('loop') }));",
        )
        assertTrue(""""guardTree":{"type":"span","props":{"children":"Guarded"}""" in output, "Unexpected guardTree in:\n$output")
        assertTrue(""""earlyTree":{"type":"div","props":{"children":{"type":"span","props":{"children":"Early"}""" in output, "Unexpected earlyTree in:\n$output")
        assertTrue(""""loopTree":{"type":"div","props":{"children":[{"type":"span","props":{"children":"0"},"key":"item-0"},{"type":"span","props":{"children":"1"},"key":"item-1"}]}""" in output, "Unexpected loopTree in:\n$output")
    }

}
