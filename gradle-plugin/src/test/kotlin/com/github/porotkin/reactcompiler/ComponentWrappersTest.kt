package com.github.porotkin.reactcompiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ComponentWrappersTest : PluginTest() {
    @Test
    fun `lowers the render lambda passed to a wrapper declared in the same module`() {
        val mjs = compile(
            """
            interface ItemProps : FragmentProps {
                var items: List<String>
                var title: String
            }

            val div: ElementType<ItemProps> = "div".unsafeCast<ElementType<ItemProps>>()
            val span: ElementType<ItemProps> = "span".unsafeCast<ElementType<ItemProps>>()

            @com.github.porotkin.reactcompiler.ComponentWrapper
            inline fun <P : Props> Popup(
                crossinline render: ChildrenBuilder.(props: P) -> Unit,
            ): FC<P> =
                memo(FC(render))

            val GuardedContainer: FC<ItemProps> = Popup { props ->
                div {
                    span { +"Header" }
                    if (props.items.isEmpty()) {
                        span { +"None" }
                        return@div
                    }
                    span { +props.title }
                }
            }

            open class Wrappers {
                @com.github.porotkin.reactcompiler.ComponentWrapper
                fun <P : Props> Member(render: ChildrenBuilder.(props: P) -> Unit): FC<P> =
                    memo(FC(render))
            }

            object Inherited : Wrappers()

            val UsesInherited: FC<ItemProps> = Inherited.Member { props -> span { +props.title } }
            """,
        )

        assertTrue("function GuardedContainer\$lambda(props)" in mjs, "Expected GuardedContainer component lowered in:\n$mjs")
        assertTrue("function UsesInherited\$lambda(props)" in mjs, "Expected the lambda of an inherited wrapper lowered in:\n$mjs")
        assertTrue("GuardedContainer = memo(render);" in mjs, "Expected Popup wrapper lowered without FC wrapper in:\n$mjs")
    }

    @Test
    fun `does not lower wrapper calls when callee invokes lambda directly or call site cannot lower`() {
        val mjs = compile(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()
            val span: ElementType<FragmentProps> = "span".unsafeCast<ElementType<FragmentProps>>()

            fun escape(builder: ChildrenBuilder) {}

            fun DirectWrap(render: ChildrenBuilder.(Props) -> Unit): FC<Props> =
                FC { props -> div { render(props) } }

            inline fun <P : Props> ForwardingWrap(
                crossinline render: ChildrenBuilder.(props: P) -> Unit,
            ): FC<P> =
                memo(FC(render))

            val UsesDirectWrap: FC<Props> = DirectWrap { _ ->
                span()
            }

            val UsesUnlowerableForwardingWrap: FC<Props> = ForwardingWrap { _ ->
                escape(this)
            }
            """,
        )

        assertTrue("function UsesDirectWrap\$lambda(\$this\$DirectWrap" in mjs, "Expected DirectWrap caller lambda not lowered in:\n$mjs")
        assertTrue("var \$this\$ForwardingWrap = get_Fragment();" in mjs, "Expected unlowerable ForwardingWrap caller lambda not lowered in:\n$mjs")
        assertTrue("Inline function 'react.FC' call" in mjs, "Expected FC wrapper preserved when call site cannot lower in:\n$mjs")
    }

    @Test
    fun `lowers lowerable and falls back to FC for unlowerable call sites of a cross-module wrapper`() {
        TestProject(dir).apply {
            module(
                "lib",
                """
                package lib

                import com.github.porotkin.reactcompiler.ComponentWrapper
                import react.*

                @ComponentWrapper
                inline fun <P : Props> Wrap(
                    crossinline render: ChildrenBuilder.(props: P) -> Unit,
                ): FC<P> =
                    memo(FC(render))

                @ComponentWrapper
                inline fun <P : Props> Outer(
                    crossinline render: ChildrenBuilder.(props: P) -> Unit,
                ): FC<P> =
                    Wrap(render)

                @ComponentWrapper
                fun <P : Props> Plain(
                    render: ChildrenBuilder.(props: P) -> Unit,
                ): FC<P> =
                    memo(FC(render))

                @ComponentWrapper
                inline fun <T> ValueWrap(
                    crossinline render: ChildrenBuilder.(props: PropsWithValue<T>) -> Unit,
                ): FC<Props> =
                    FC(render).unsafeCast<FC<Props>>()
                """,
            )
            module(
                "app",
                """
                package app

                import lib.*
                import react.*

                val span: ElementType<FragmentProps> = "span".unsafeCast<ElementType<FragmentProps>>()

                var escaped = 0

                fun escape(builder: ChildrenBuilder) {
                    escaped++
                }

                val Lowerable: FC<Props> = Wrap { _ -> span { +"w" } }

                val Unlowerable: FC<Props> = Wrap { _ -> escape(this) }

                val OuterLowerable: FC<Props> = Outer { _ -> span { +"o" } }

                val OuterUnlowerable: FC<Props> = Outer { _ -> escape(this) }

                val renderVar: ChildrenBuilder.(Props) -> Unit = { escape(this) }

                val VariableWrapped: FC<Props> = Plain(renderVar)

                val ValueLowerable: FC<Props> = ValueWrap<String> { props -> span { +props.value } }

                val ValueUnlowerable: FC<Props> = ValueWrap<String> { _ -> escape(this) }

                @JsExport
                fun renderLowerable(): Any? =
                    Lowerable.asDynamic()(js("({})"))

                @JsExport
                fun renderOuterLowerable(): Any? =
                    OuterLowerable.asDynamic()(js("({})"))

                @JsExport
                fun renderValueLowerable(): Any? =
                    ValueLowerable.asDynamic()(js("({ value: 'v' })"))

                @JsExport
                fun callUnlowerable(): Int {
                    Unlowerable.asDynamic()(js("({})"))
                    OuterUnlowerable.asDynamic()(js("({})"))
                    VariableWrapped.asDynamic()(js("({})"))
                    ValueUnlowerable.asDynamic()(js("({ value: 'v' })"))
                    return escaped
                }
                """,
                "lib",
            )
        }.run(":app:compileDevelopmentExecutableKotlinJs")

        val output = runNode(
            "app",
            "console.log(JSON.stringify({ lowered: m.renderLowerable(), outer: m.renderOuterLowerable(), valWrap: m.renderValueLowerable(), fallbackCount: m.callUnlowerable() }));",
        )
        assertTrue(""""lowered":{"type":"span","props":{"children":"w"}""" in output, "Unexpected lowered tree in:\n$output")
        assertTrue(""""outer":{"type":"span","props":{"children":"o"}""" in output, "Unexpected outer tree in:\n$output")
        assertTrue(""""valWrap":{"type":"span","props":{"children":"v"}""" in output, "Unexpected valWrap tree in:\n$output")
        assertTrue(""""fallbackCount":4""" in output, "Expected FC fallback to run all raw builder lambdas in:\n$output")
    }

    @Test
    fun `fails the build when a module compiled without the plugin calls a component wrapper`() {
        val project = TestProject(dir).apply {
            module(
                "lib",
                """
                package lib

                import com.github.porotkin.reactcompiler.ComponentWrapper
                import react.*

                @ComponentWrapper
                fun <P : Props> Wrap(
                    render: ChildrenBuilder.(props: P) -> Unit,
                ): FC<P> =
                    memo(FC(render))
                """,
            )
            module(
                "app",
                """
                package app

                import lib.*
                import react.*

                val Unplugged: FC<Props> = Wrap { _ -> }
                """,
                "lib",
                applyPlugin = false,
            )
        }

        val output = project.runAndFail(":app:compileDevelopmentExecutableKotlinJs").output
        assertTrue(
            "Call sites of a @ComponentWrapper function must be compiled with the Kotlin React Compiler plugin." in output,
            "Expected opt-in error in:\n$output",
        )
    }

    @Test
    fun `fails the build when a module compiled with the plugin calls a wrapper declared without it`() {
        val project = TestProject(dir).apply {
            module(
                "lib",
                """
                package lib

                import com.github.porotkin.reactcompiler.ComponentWrapper
                import react.*

                @ComponentWrapper
                fun <P : Props> Plain(
                    render: ChildrenBuilder.(props: P) -> Unit,
                ): FC<P> =
                    memo(FC(render))

                @ComponentWrapper
                inline fun <P : Props> Inline(
                    crossinline render: ChildrenBuilder.(props: P) -> Unit,
                ): FC<P> =
                    memo(FC(render))
                """,
                applyPlugin = false,
            )
            module(
                "app",
                """
                package app

                import com.github.porotkin.reactcompiler.ComponentWrapper
                import lib.*
                import react.*

                val UsesPlain: FC<Props> = Plain { _ -> }

                val UsesInline: FC<Props> = Inline { _ -> }

                @ComponentWrapper
                fun Forwarding(render: ChildrenBuilder.(Props) -> Unit): FC<Props> =
                    Plain(render)
                """,
                "lib",
            )
        }

        val output = project.runAndFail(":app:compileDevelopmentExecutableKotlinJs").output
        for (callSite in listOf("App.kt:7:28 @ComponentWrapper Plain", "App.kt:9:29 @ComponentWrapper Inline", "App.kt:13:5 @ComponentWrapper Plain")) {
            assertTrue(
                "$callSite was not rewritten by the Kotlin React Compiler plugin: apply the plugin to the module that declares it" in output,
                "Expected an error at $callSite in:\n$output",
            )
        }
    }

    @Test
    fun `trusts a wrapper declared in a file an incremental build does not recompile`() {
        val project = TestProject(dir).apply {
            module(
                "app",
                """
                package app

                import com.github.porotkin.reactcompiler.ComponentWrapper
                import react.*

                @ComponentWrapper
                fun <P : Props> Wrap(
                    render: ChildrenBuilder.(props: P) -> Unit,
                ): FC<P> =
                    memo(FC(render))
                """,
            )
        }
        val caller = File(dir, "app/src/jsMain/kotlin/Caller.kt")
        caller.writeText("package app\n\nimport react.*\n\nval First: FC<Props> = Wrap { _ -> }\n")
        project.run(":app:compileDevelopmentExecutableKotlinJs")

        caller.appendText("\nval Second: FC<Props> = Wrap { _ -> }\n")
        val output = project.run(":app:compileDevelopmentExecutableKotlinJs", "--debug").output
        val recompiled = output.lines().filter { "compile iteration: " in it }.map { it.substringAfter("compile iteration: ") }
        assertEquals(listOf(caller.relativeTo(dir).path), recompiled, "Expected an incremental build of the caller only")
    }

    @Test
    fun `fails the build when a component wrapper has no body, is open or overrides`() {
        val project = TestProject(dir).apply {
            module(
                "app",
                """
                package app

                import com.github.porotkin.reactcompiler.ComponentWrapper
                import react.*

                abstract class Wrappers {
                    @ComponentWrapper
                    abstract fun Abstract(render: ChildrenBuilder.(Props) -> Unit): FC<Props>

                    @ComponentWrapper
                    open fun Open(render: ChildrenBuilder.(Props) -> Unit): FC<Props> =
                        FC(render)

                    abstract fun Plain(render: ChildrenBuilder.(Props) -> Unit): FC<Props>
                }

                object Memoized : Wrappers() {
                    override fun Abstract(render: ChildrenBuilder.(Props) -> Unit): FC<Props> =
                        memo(FC(render))

                    @ComponentWrapper
                    override fun Plain(render: ChildrenBuilder.(Props) -> Unit): FC<Props> =
                        memo(FC(render))
                }
                """,
            )
        }

        val errors = project.runAndFail(":app:compileDevelopmentExecutableKotlinJs").output.lines().filter { it.startsWith("e: ") }
        // `Memoized` inherits `Open`: the inherited copy is not reported again.
        assertEquals(
            listOf("App.kt:8:14 @ComponentWrapper Abstract", "App.kt:11:10 @ComponentWrapper Open", "App.kt:22:14 @ComponentWrapper Plain")
                .map { "$it must be a final, non-overriding function with a body" },
            errors.map { it.substringAfterLast('/') },
        )
    }

    @Test
    fun `fails the build when a component wrapper does not only forward its render lambda`() {
        val project = TestProject(dir).apply {
            module(
                "app",
                """
                package app

                import com.github.porotkin.reactcompiler.ComponentWrapper
                import react.*

                val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

                @ComponentWrapper
                fun DirectWrap(render: ChildrenBuilder.(Props) -> Unit): FC<Props> =
                    FC { props -> div { render(props) } }
                """,
            )
        }

        val output = project.runAndFail(":app:compileDevelopmentExecutableKotlinJs").output
        assertTrue("must use its render parameter only as FC(render)" in output, "Expected wrapper verification error in:\n$output")
    }
}
