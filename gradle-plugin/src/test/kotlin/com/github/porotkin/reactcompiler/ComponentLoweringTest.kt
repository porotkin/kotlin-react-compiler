package com.github.porotkin.reactcompiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

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

        assertTrue(isLowered(mjs, "Pair\$lambda"), "Expected lowered component in:\n$mjs")
        assertTrue("throwIrLinkageError" !in mjs, "Expected a linkable Fragment jsx call in:\n$mjs")
        assertTrue(Regex("""jsxs\(get_Fragment\(\), \w+\)""") in mjs, "Expected keyless Fragment jsxs call in:\n$mjs")
    }

    @Test
    fun `passes several children to jsxs, without a null key`() {
        val mjs = compile(
            """
            interface LabelProps : FragmentProps {
                var label: String?
            }

            val div: ElementType<LabelProps> = "div".unsafeCast<ElementType<LabelProps>>()

            val Labels: FC<LabelProps> = FC { props ->
                div {
                    +"a"
                    +"b"
                }
                div {
                    key = props.label
                    +"c"
                    +"d"
                }
                div { +"e" }
            }
            """,
        )

        assertTrue(Regex("""import \{ jsxs as jsxs \} from 'react/jsx-runtime'""") in mjs, "Expected React's jsxs imported in:\n$mjs")
        assertTrue(Regex("""jsxs\(type, attrs, '@rdk/\d+_\d+'\)""") in mjs, "Expected source-keyed jsxs call in:\n$mjs")
        assertTrue(Regex("""jsxs\(type\w*, attrs\w*, (key\w*) == null \? undefined : \1\)""") in mjs, "Expected nullable key passed as undefined in:\n$mjs")
        assertTrue(Regex("""[^s]jsx\(type\w*, attrs\w*, '@rdk/\d+_\d+'\)""") in mjs, "Expected single child kept on jsx in:\n$mjs")
    }

    @Test
    fun `maps the code built for elements and hook calls to their lines in the source map`() {
        compile(
            """
            interface LabelProps : FragmentProps {
                var title: String
            }

            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()
            val span: ElementType<LabelProps> = "span".unsafeCast<ElementType<LabelProps>>()

            val Card: FC<Props> = FC { props ->
                val label = useMemo(props.key) { "label" }
                div {
                    span {
                        title = label
                        +label
                    }
                }
            }
            """,
        )
        val mjs = dir.walkTopDown().first { it.name.endsWith("-app.mjs") }
        val mapped = runNode(
            "app",
            """
            const fs = await import('node:fs');
            const { SourceMap } = await import('node:module');
            const js = fs.readFileSync('$mjs', 'utf8').split('\n');
            const kotlin = fs.readFileSync('${File(dir, "app/src/jsMain/kotlin/App.kt")}', 'utf8').split('\n');
            const map = new SourceMap(JSON.parse(fs.readFileSync('$mjs.map', 'utf8')));
            for (let line = js.indexOf("  'use memo';", js.indexOf('function Card${'$'}lambda(props) {')); js[line] !== '}'; line++) {
              const sources = new Set();
              for (let column = 0; column < js[line].length; column++) {
                const entry = map.findEntry(line, column);
                if (entry.generatedLine === line && entry.generatedColumn === column && entry.originalSource) sources.add(kotlin[entry.originalLine].trim());
              }
              console.log(js[line].trim().replace(/_[a-z0-9]+_k\${'$'}|@rdk\/\w+/g, '') + ' <- ' + [...sources].join(' | '));
            }
            """,
        )

        assertEquals(
            """
            'use memo'; <- val Card: FC<Props> = FC { props ->
            var dep = props.get_key(); <- val label = useMemo(props.key) { "label" }
            var label = useMemoRaw(Card${'$'}lambda${'$'}lambda, [dep]); <- val label = useMemo(props.key) { "label" }
            var type = get_div(); <- div {
            var type_0 = get_span(); <- span {
            var attrs = {}; <- span {
            attrs.set_title(label); <- title = label
            attrs.set_children(label); <- span { | +label
            var el = jsx(type_0, attrs, ''); <- span {
            var attrs_0 = {}; <- div {
            attrs_0.set_children(el); <- div { | span {
            var el_0 = jsx(type, attrs_0, ''); <- div {
            return el_0; <- div {
            """.trimIndent(),
            mapped,
        )
    }

    @Test
    fun `notes the statement that keeps a component from being lowered, in an --info build only`() {
        val project = TestProject(dir).apply {
            module(
                "app",
                """
                package app

                import react.*

                val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

                fun render(builder: ChildrenBuilder) {}

                val Escaping: FC<Props> = FC { _ ->
                    div {
                        render(this)
                    }
                }
                """,
            )
        }
        val note = "App.kt:11:9 The plugin cannot lower this code: its component stays as it is, and React Compiler does not memoize it"

        assertTrue(note !in project.run(":app:compileKotlinJs").output, "Expected no note in a plain build")
        val output = project.run(":app:compileKotlinJs", "--info", "--rerun-tasks").output
        assertTrue(note in output, "Expected the escaping builder noted in:\n$output")
    }

    @Test
    fun `lowers a component calling run, which takes the builder as its unused receiver`() {
        val mjs = compile(
            """
            val div: ElementType<PropsWithValue<String>> = "div".unsafeCast<ElementType<PropsWithValue<String>>>()

            val Computed: FC<PropsWithValue<String>> = FC { props ->
                val label = run { props.value + "!" }
                div {
                    value = run { label + "?" }
                    +label
                }
            }

            @JsExport
            fun renderComputed(): Any? =
                Computed.asDynamic()(js("({ value: 'a' })"))
            """,
        )

        assertTrue("'use memo';" in mjs, "Expected lowered component in:\n$mjs")
        assertEquals("""{"value":"a!?","children":"a!"}""", runNode("app", "console.log(JSON.stringify(m.renderComputed().props));"))
    }

    @Test
    fun `adds what a branch, a block or a nested loop adds in a loop to its list one by one`() {
        compile(
            """
            val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

            val Rows: FC<PropsWithValue<Array<String>>> = FC { props ->
                div {
                    for (item in props.value) {
                        if (item != "skip") {
                            div {
                                key = item
                            }
                            div {
                                key = item + "-note"
                            }
                        } else {
                            div {
                                key = "skipped"
                            }
                        }
                        item.takeIf { it == "b" }?.let { last ->
                            div {
                                key = last + "-last"
                            }
                        }
                        repeat(1) {
                            div {
                                key = item + "-end"
                            }
                        }
                    }
                }
            }

            @JsExport
            fun renderRows(): Any? =
                Rows.asDynamic()(js("({ value: ['a', 'skip', 'b'] })"))
            """,
        )

        // Wrapped in a Fragment, the two rows of the branch would be one list item without a key.
        assertEquals(
            """["a","a-note","a-end","skipped","skip-end","b","b-note","b-last","b-end"]""",
            runNode("app", "console.log(JSON.stringify(m.renderRows().props.children.map((child) => child.key)));"),
        )
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
    fun `sets a provider value and element props on the attrs object`() {
        val mjs = compile(
            """
            interface StateProps : PropsWithChildren {
                var value: Int
                var setter: (Int) -> Unit
            }

            val ThemeContext: Provider<String> = "ThemeContext".unsafeCast<Provider<String>>()
            val StateContext: ElementType<StateProps> = "StateContext".unsafeCast<ElementType<StateProps>>()

            val Themed: FC<Props> = FC { props ->
                val theme = props.asDynamic().theme as String
                ThemeContext(theme) {
                    StateContext {
                        value = 1
                        setter = {}
                    }
                }
            }
            """,
        )

        assertTrue(Regex("""attrs\w*\.value = theme;""") in mjs, "Expected Provider value set on attrs in:\n$mjs")
        assertTrue(Regex("""attrs\w*\.set_value_[\w$]+\(1\);""") in mjs, "Expected element prop set on attrs in:\n$mjs")
        assertTrue(Regex("""attrs\w*\.set_setter_[\w$]+\(""") in mjs, "Expected element prop set on attrs in:\n$mjs")
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

        assertTrue(isLowered(mjs, "Styled\$lambda"), "Expected Styled lowered in:\n$mjs")
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

        assertTrue(isLowered(mjs, "TailAndChildView\$lambda"), "Expected TailAndChildView lowered in:\n$mjs")
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

        assertTrue(isLowered(mjs, "LetView\$lambda"), "Expected LetView lowered in:\n$mjs")
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

        assertTrue(isLowered(mjs, "SubjectWhen\$lambda"), "Expected SubjectWhen lowered in:\n$mjs")
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

        assertTrue(isLowered(mjs, "ConditionalKeyView\$lambda"), "Expected ConditionalKeyView lowered in:\n$mjs")
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
        assertTrue("(\$this\$FC, 1, 2, UsesPlainFn4\$lambda\$lambda);" in mjs, "Expected plain Function4 called with builder receiver in:\n$mjs")
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

    @Test
    fun `lowers cross-module generic ValueContext wrapper invoke and preserves runtime fallback and escape checks`() {
        TestProject(dir).apply {
            module(
                "ctx",
                """
                package ctx

                import com.github.porotkin.reactcompiler.ElementWrapper
                import react.*

                class ValueContext<T> internal constructor(
                    internal val value: Provider<T>,
                    internal val setter: Provider<(T) -> Unit>,
                    val component: FC<ValueContextProps<T>>,
                ) {
                    @ElementWrapper
                    context(builder: ChildrenBuilder)
                    operator fun invoke(
                        value: T,
                        setter: (T) -> Unit = {},
                        block: ChildrenBuilder.() -> Unit,
                    ) {
                        with(builder) {
                            component {
                                this.value = value
                                this.setter = setter
                                block()
                            }
                        }
                    }
                }

                class UnannotatedWrapper(
                    val component: FC<ValueContextProps<Int>>,
                ) {
                    context(builder: ChildrenBuilder)
                    operator fun invoke(
                        value: Int,
                        setter: (Int) -> Unit,
                        block: ChildrenBuilder.() -> Unit,
                    ) {
                        with(builder) {
                            component {
                                this.value = value + 100
                                this.setter = setter
                                block()
                            }
                        }
                    }
                }

                interface JsNamedSetterProps<T> : PropsWithChildren {
                    @get:JsName("getCustomValue")
                    @set:JsName("setCustomValue")
                    var value: T

                    @get:JsName("getCustomSetter")
                    @set:JsName("setCustomSetter")
                    var setter: (T) -> Unit
                }

                class JsNamedValueContext<T>(
                    val component: FC<JsNamedSetterProps<T>>,
                ) {
                    @ElementWrapper
                    context(builder: ChildrenBuilder)
                    operator fun invoke(
                        value: T,
                        setter: (T) -> Unit,
                        block: ChildrenBuilder.() -> Unit,
                    ) {
                        with(builder) {
                            component {
                                this.value = value
                                this.setter = setter
                                block()
                            }
                        }
                    }
                }

                fun <T> createJsNamedValueContext(tag: String): JsNamedValueContext<T> {
                    val valueProvider = "${'$'}tag:value".unsafeCast<Provider<T>>()
                    val setterProvider = "${'$'}tag:setter".unsafeCast<Provider<(T) -> Unit>>()
                    val component: FC<JsNamedSetterProps<T>> = FC { props ->
                        setterProvider(props.setter) {
                            valueProvider(props.value) {
                                +props.children.toString()
                            }
                        }
                    }
                    return JsNamedValueContext(component)
                }

                fun <T> createValueContext(tag: String): ValueContext<T> {
                    val valueProvider = "${'$'}tag:value".unsafeCast<Provider<T>>()
                    val setterProvider = "${'$'}tag:setter".unsafeCast<Provider<(T) -> Unit>>()
                    val component: FC<ValueContextProps<T>> = FC { props ->
                        setterProvider(props.setter) {
                            valueProvider(props.value) {
                                +props.children.toString()
                            }
                        }
                    }
                    return ValueContext(valueProvider, setterProvider, component)
                }
                """,
            )
            module(
                "untrusted",
                """
                package untrusted

                import com.github.porotkin.reactcompiler.ElementWrapper
                import react.*

                class UntrustedWrapper(
                    val component: FC<ValueContextProps<Int>>,
                ) {
                    @ElementWrapper
                    context(builder: ChildrenBuilder)
                    operator fun invoke(
                        value: Int,
                        setter: (Int) -> Unit,
                        block: ChildrenBuilder.() -> Unit,
                    ) {
                        // Body does something unrelated in a module compiled without the plugin
                    }
                }
                """,
                applyPlugin = false,
            )
            module(
                "app",
                """
                package app

                import ctx.*
                import untrusted.*
                import react.*

                val span: ElementType<FragmentProps> = "span".unsafeCast<ElementType<FragmentProps>>()
                val CountContext: ValueContext<Int> = createValueContext("Count")
                val LabelContext: ValueContext<String> = createValueContext("Label")
                val JsNamedContext: JsNamedValueContext<Int> = createJsNamedValueContext("JsNamed")
                val UnannotatedCtx: UnannotatedWrapper = UnannotatedWrapper(CountContext.component)
                val UntrustedCtx: UntrustedWrapper = UntrustedWrapper(CountContext.component)

                val ProvidedView: FC<Props> = FC { props ->
                    val count = props.asDynamic().count as Int
                    val label = props.asDynamic().label as String
                    val setCount: (Int) -> Unit = {}
                    val setLabel: (String) -> Unit = {}
                    CountContext(count, setCount) {
                        LabelContext(label, setLabel) {
                            span { +label }
                        }
                    }
                }

                val JsNamedProvidedView: FC<Props> = FC { props ->
                    val count = props.asDynamic().count as Int
                    val setCount: (Int) -> Unit = {}
                    JsNamedContext(count, setCount) {
                        span { +"js-named" }
                    }
                }

                val UsesUnannotated: FC<Props> = FC { _ ->
                    UnannotatedCtx(1, {}) {
                        span { +"unannotated" }
                    }
                }

                val UsesUntrusted: FC<Props> = FC { _ ->
                    UntrustedCtx(1, {}) {
                        span { +"untrusted" }
                    }
                }

                val UsesOmittedDefault: FC<Props> = FC { _ ->
                    CountContext(1) {
                        span { +"defaulted" }
                    }
                }

                fun escapeBuilder(builder: ChildrenBuilder): Int = 0

                val EscapesInArg: FC<Props> = FC { _ ->
                    CountContext(escapeBuilder(this), {}) {
                        span { +"bad" }
                    }
                }

                val EscapesInBody: FC<Props> = FC { _ ->
                    CountContext(1, {}) {
                        escapeBuilder(this)
                        span { +"bad" }
                    }
                }

                class RecordingBuilder : ChildrenBuilder {
                    val recorded = js("([])")

                    override fun <P : Props> ElementType<P>.invoke(block: P.() -> Unit) {
                        val p = RecordingBuilder().unsafeCast<P>()
                        p.block()
                        recorded.push(react.jsx.runtime.jsx(this, p, "fallback-key"))
                    }

                    override fun String.unaryPlus() {
                        this@RecordingBuilder.asDynamic().children = this
                    }
                }

                fun buildFallback(builder: ChildrenBuilder, count: Int, setter: (Int) -> Unit) {
                    with(builder) {
                        CountContext(count, setter) {
                            +"fallback-${'$'}count"
                        }
                    }
                }

                @JsExport
                fun runChecks(): Any? {
                    val loweredTree = ProvidedView.asDynamic()(js("({ count: 7, label: 'ok' })"))
                    val loweredRendered = loweredTree.type(loweredTree.props)
                    val builder = RecordingBuilder()
                    val setter: (Int) -> Unit = {}
                    buildFallback(builder, 42, setter)
                    val fallbackElement = builder.recorded[0]
                    val fallbackRendered = fallbackElement.type(fallbackElement.props)
                    return js("({ loweredTree: loweredTree, loweredRendered: loweredRendered, fallbackValue: fallbackElement.props.value, fallbackRendered: fallbackRendered })")
                }
                """,
                "ctx",
                "untrusted",
            )
        }.run(":app:compileDevelopmentExecutableKotlinJs")

        val mjs = dir.walkTopDown().first { it.name.endsWith("-app.mjs") }.readText()
        assertTrue(isLowered(mjs, "ProvidedView\$lambda"), "Expected ProvidedView lowered in:\n$mjs")
        assertTrue(Regex("""get_CountContext\(\)\.get_component_[\w$]+\(\)""") in mjs, "Expected CountContext.component getter used as jsx type in:\n$mjs")
        assertTrue(Regex("""attrs\w*\.set_value_[\w$]+\(count\);""") in mjs, "Expected non-external value prop set via typed mangled setter on attrs in:\n$mjs")
        assertTrue(Regex("""attrs\w*\.set_setter_[\w$]+\(setCount\);""") in mjs, "Expected non-external setter prop set via typed mangled setter on attrs in:\n$mjs")
        assertTrue(Regex("""attrs\w*\.setCustomValue\(count\);""") in mjs, "Expected @set:JsName('setCustomValue') prop set via custom setter on attrs in:\n$mjs")
        assertTrue(Regex("""attrs\w*\.setCustomSetter\(setCount\);""") in mjs, "Expected @set:JsName('setCustomSetter') prop set via custom setter on attrs in:\n$mjs")
        assertTrue(Regex("get_UnannotatedCtx\\(\\);[\\s\\S]*?invoke_[\\w$]+\\(\\\$this\\\$FC, 1, \\w+, UsesUnannotated\\\$lambda\\\$lambda_0\\)") in mjs, "Expected unannotated wrapper invoke not lowered in:\n$mjs")
        assertTrue(Regex("get_UntrustedCtx\\(\\);[\\s\\S]*?invoke_[\\w$]+\\(\\\$this\\\$FC, 1, \\w+, UsesUntrusted\\\$lambda\\\$lambda_0\\)") in mjs, "Expected untrusted external-module wrapper invoke not lowered in:\n$mjs")
        assertTrue(Regex("invoke\\\$default_[\\w$]+\\(\\\$this\\\$FC, 1, VOID, UsesOmittedDefault\\\$lambda\\\$lambda\\)") in mjs, "Expected omitted default argument call to bail safely without crashing in:\n$mjs")
        assertTrue("escapeBuilder(\$this\$FC)" in mjs, "Expected EscapesInArg to bail when builder escapes in arg in:\n$mjs")
        assertTrue("escapeBuilder(\$this\$CountContext)" in mjs, "Expected EscapesInBody to bail when builder escapes in body in:\n$mjs")

        val output = runNode("app", "console.log(JSON.stringify(m.runChecks()));")
        assertTrue(""""fallbackValue":42""" in output, "Expected unlowered fallback to populate value prop in:\n$output")
        assertTrue(""""fallbackRendered":{"type":"Count:setter","props":{"children":{"type":"Count:value","props":{"value":42,"children":"fallback-42"}""" in output, "Expected unlowered fallback component to render in:\n$output")
        assertTrue(""""loweredRendered":{"type":"Count:setter","props":{"children":{"type":"Count:value","props":{"value":7""" in output, "Expected lowered tree component to execute and render count=7 in:\n$output")
        assertTrue(""""loweredTree":{"props":{"value":7,"children":{"props":{"value":"ok","children":{"type":"span","props":{"children":"ok"},"key":"@rdk/20_12"}},"key":"@rdk/19_8"}},"key":"@rdk/18_4"}""" in output, "Expected lowered tree to pass count=7 and label='ok' with keys in:\n$output")

        val externalDir = dir.resolve("external-jsname")
        TestProject(externalDir, externalProps = true).apply {
            module(
                "app",
                """
                package app

                import com.github.porotkin.reactcompiler.ElementWrapper
                import react.*

                external interface ExternalJsNamedProps<T> : PropsWithChildren {
                    @JsName("v")
                    var value: T

                    @JsName("s")
                    var setter: (T) -> Unit
                }

                class ExternalJsNamedContext<T>(
                    val component: FC<ExternalJsNamedProps<T>>,
                ) {
                    @ElementWrapper
                    context(builder: ChildrenBuilder)
                    operator fun invoke(
                        value: T,
                        setter: (T) -> Unit,
                        block: ChildrenBuilder.() -> Unit,
                    ) {
                        with(builder) {
                            component {
                                this.value = value
                                this.setter = setter
                                block()
                            }
                        }
                    }
                }

                val valueProvider: Provider<Int> = "Ext:value".unsafeCast<Provider<Int>>()
                val setterProvider: Provider<(Int) -> Unit> = "Ext:setter".unsafeCast<Provider<(Int) -> Unit>>()
                val span: ElementType<FragmentProps> = "span".unsafeCast<ElementType<FragmentProps>>()

                val ExtContext: ExternalJsNamedContext<Int> = ExternalJsNamedContext(
                    FC { props ->
                        setterProvider(props.setter) {
                            valueProvider(props.value) {
                                +props.children.toString()
                            }
                        }
                    },
                )

                val ExtView: FC<Props> = FC { props ->
                    val count = props.asDynamic().count as Int
                    val setCount: (Int) -> Unit = {}
                    ExtContext(count, setCount) {
                        span { +"ext" }
                    }
                }

                fun buildExtFallback(builder: ChildrenBuilder, count: Int, setter: (Int) -> Unit) {
                    with(builder) {
                        ExtContext(count, setter) {
                            +"ext-fallback-${'$'}count"
                        }
                    }
                }

                @JsExport
                fun runExtChecks(): Any? {
                    val tree = ExtView.asDynamic()(js("({ count: 11 })"))
                    val rendered = tree.type(tree.props)
                    val fbBuilder = js("({ recorded: [] })").unsafeCast<ChildrenBuilder>()
                    buildExtFallback(fbBuilder, 19, {})
                    val fbElement = fbBuilder.asDynamic().recorded[0]
                    val fbRendered = fbElement.type(fbElement.props)
                    return js("({ v: tree.props.v, hasRawValue: ('value' in tree.props), rendered: rendered, fbV: fbElement.props.v, fbHasRawValue: ('value' in fbElement.props), fbRendered: fbRendered })")
                }
                """,
            )
        }.run(":app:compileDevelopmentExecutableKotlinJs")

        val extMjs = externalDir.walkTopDown().first { it.name.endsWith("-app.mjs") }.readText()
        assertTrue(Regex("""attrs\w*\.v = count;""") in extMjs, "Expected external @JsName('v') prop set on attrs.v in:\n$extMjs")
        assertTrue(Regex("""attrs\w*\.s = setCount;""") in extMjs, "Expected external @JsName('s') prop set on attrs.s in:\n$extMjs")
        val extOutput = runNode("app", "console.log(JSON.stringify(m.runExtChecks()));", externalDir)
        assertTrue(""""v":11,"hasRawValue":false""" in extOutput, "Expected external @JsName('v') prop stored under .v (not .value) in:\n$extOutput")
        assertTrue(""""rendered":{"type":"Ext:setter","props":{"children":{"type":"Ext:value","props":{"value":11""" in extOutput, "Expected external @JsName component to execute and read props.v=11 in:\n$extOutput")
        assertTrue(""""fbV":19,"fbHasRawValue":false""" in extOutput, "Expected external @JsName fallback to store .v=19 in:\n$extOutput")
        assertTrue(""""fbRendered":{"type":"Ext:setter","props":{"children":{"type":"Ext:value","props":{"value":19,"children":"ext-fallback-19"}""" in extOutput, "Expected external @JsName fallback component to render in:\n$extOutput")
    }

    @Test
    fun `fails the build when an ElementWrapper has a mismatched prop name, non-conforming body, or invalid signature`() {
        val mismatchedProp = TestProject(dir.resolve("mismatched")).apply {
            module(
                "app",
                """
                package app

                import com.github.porotkin.reactcompiler.ElementWrapper
                import react.*

                class BadPropWrapper(
                    val component: FC<ValueContextProps<Int>>,
                ) {
                    @ElementWrapper
                    context(builder: ChildrenBuilder)
                    operator fun invoke(
                        wrongName: Int,
                        setter: (Int) -> Unit,
                        block: ChildrenBuilder.() -> Unit,
                    ) {
                        with(builder) {
                            component {
                                this.value = wrongName
                                this.setter = setter
                                block()
                            }
                        }
                    }
                }
                """,
            )
        }.runAndFail(":app:compileDevelopmentExecutableKotlinJs").output
        assertTrue("parameter 'wrongName' must match a writable property on ValueContextProps" in mismatchedProp, "Expected prop mismatch diagnostic in:\n$mismatchedProp")

        val badBody = TestProject(dir.resolve("bad-body")).apply {
            module(
                "app",
                """
                package app

                import com.github.porotkin.reactcompiler.ElementWrapper
                import react.*

                var sideEffect = 0

                class BadBodyWrapper(
                    val component: FC<ValueContextProps<Int>>,
                ) {
                    @ElementWrapper
                    context(builder: ChildrenBuilder)
                    operator fun invoke(
                        value: Int,
                        setter: (Int) -> Unit,
                        block: ChildrenBuilder.() -> Unit,
                    ) {
                        sideEffect++
                        with(builder) {
                            component {
                                this.value = value
                                this.setter = setter
                                block()
                            }
                        }
                    }
                }
                """,
            )
        }.runAndFail(":app:compileDevelopmentExecutableKotlinJs").output
        assertTrue("must only delegate to its ElementType property, assigning each parameter to the matching prop and calling block()" in badBody, "Expected body diagnostic in:\n$badBody")

        val badSignature = TestProject(dir.resolve("bad-sig")).apply {
            module(
                "app",
                """
                package app

                import com.github.porotkin.reactcompiler.ElementWrapper
                import react.*

                class BadSigWrapper(
                    val component: FC<ValueContextProps<Int>>,
                ) {
                    @ElementWrapper
                    context(builder: ChildrenBuilder)
                    operator fun invoke(
                        vararg value: Int,
                        block: ChildrenBuilder.() -> Unit,
                    ) {}
                }
                """,
            )
        }.runAndFail(":app:compileDevelopmentExecutableKotlinJs").output
        assertTrue("must be a final, non-overriding, non-inline, non-external operator fun invoke on a class with one public ElementType property" in badSignature, "Expected signature diagnostic in:\n$badSignature")

        val openInvoke = TestProject(dir.resolve("open-invoke")).apply {
            module(
                "app",
                """
                package app

                import com.github.porotkin.reactcompiler.ElementWrapper
                import react.*

                open class OpenWrapper(
                    val component: FC<ValueContextProps<Int>>,
                ) {
                    @ElementWrapper
                    context(builder: ChildrenBuilder)
                    open operator fun invoke(
                        value: Int,
                        setter: (Int) -> Unit,
                        block: ChildrenBuilder.() -> Unit,
                    ) {
                        with(builder) {
                            component {
                                this.value = value
                                this.setter = setter
                                block()
                            }
                        }
                    }
                }
                """,
            )
        }.runAndFail(":app:compileDevelopmentExecutableKotlinJs").output
        assertTrue("must be a final, non-overriding, non-inline, non-external operator fun invoke on a class with one public ElementType property" in openInvoke, "Expected open invoke diagnostic in:\n$openInvoke")

        val overrideInvoke = TestProject(dir.resolve("override-invoke")).apply {
            module(
                "app",
                """
                package app

                import com.github.porotkin.reactcompiler.ElementWrapper
                import react.*

                interface InvokableWrapper {
                    context(builder: ChildrenBuilder)
                    operator fun invoke(
                        value: Int,
                        setter: (Int) -> Unit,
                        block: ChildrenBuilder.() -> Unit,
                    )
                }

                class OverrideWrapper(
                    val component: FC<ValueContextProps<Int>>,
                ) : InvokableWrapper {
                    @ElementWrapper
                    context(builder: ChildrenBuilder)
                    override operator fun invoke(
                        value: Int,
                        setter: (Int) -> Unit,
                        block: ChildrenBuilder.() -> Unit,
                    ) {
                        with(builder) {
                            component {
                                this.value = value
                                this.setter = setter
                                block()
                            }
                        }
                    }
                }
                """,
            )
        }.runAndFail(":app:compileDevelopmentExecutableKotlinJs").output
        assertTrue("must be a final, non-overriding, non-inline, non-external operator fun invoke on a class with one public ElementType property" in overrideInvoke, "Expected override invoke diagnostic in:\n$overrideInvoke")
    }
}
