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
}
