package com.github.porotkin.reactcompiler

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class ReactRuntimeTest : PluginTest() {
    @Test
    fun `renders conditional helpers, memoizes helper args, and preserves hook helper state in React 19`() {
        TestProject(dir, realReact = true).apply {
            module(
                "lib",
                """
                package lib

                import com.github.porotkin.reactcompiler.ComponentWrapper
                import react.*

                @ComponentWrapper
                fun <P : Props> Memoized(
                    render: ChildrenBuilder.(props: P) -> Unit,
                ): FC<P> =
                    memo(FC(render))
                """,
            )
            module(
                "app",
                """
                @file:OptIn(kotlin.js.ExperimentalJsExport::class)
                @file:Suppress("REACT_ONLY_EXPORT_COMPONENTS")

                package app

                import js.coroutines.awaitCancellation
                import lib.Memoized
                import react.*

                external interface PageProps : Props {
                    var show: Boolean
                    var title: String
                    var unrelated: Int
                }

                external interface ButtonProps : FragmentProps {
                    var onClick: () -> Unit
                }

                private val span: ElementType<FragmentProps> = "span".unsafeCast<ElementType<FragmentProps>>()
                private val b: ElementType<FragmentProps> = "b".unsafeCast<ElementType<FragmentProps>>()
                private val button: ElementType<ButtonProps> = "button".unsafeCast<ElementType<ButtonProps>>()

                var footerRenders = 0
                var effectHistory = ""
                var unmountCleanups = 0

                class Formatter(val sep: String) {
                    fun format(label: String, count: Int): String = "${'$'}label${'$'}sep${'$'}count"
                }

                fun defaultSeparator(): String = ":"

                value class Tag(val raw: String)

                private fun ChildrenBuilder.Heading(title: String) {
                    span { +title }
                }

                @Suppress("REACT_GLOBALS")
                private fun ChildrenBuilder.Footer(title: String) {
                    footerRenders++
                    b { +title }
                }

                @Suppress("REACT_GLOBALS")
                private fun ChildrenBuilder.InnerCounter(label: String) {
                    var count by useState(0)
                    var commits by useRefValue(0)
                    var lastTag by useRefValue(Tag("none"))
                    val sep = useMemo(callback = ::defaultSeparator)
                    val suffix = useMemo { "" }
                    val formatter = useMemo(sep) { Formatter(sep) }
                    val formatCount = useCallback(formatter, callback = formatter::format)
                    val text = useMemo(label, count, formatCount, suffix) { formatCount(label, count) + suffix }

                    useEffect(label, count) {
                        commits += 1
                        effectHistory += "${'$'}label=${'$'}count#${'$'}commits<${'$'}{lastTag.raw};"
                        lastTag = Tag("${'$'}label${'$'}count")
                    }

                    useEffectOnce {
                        awaitCancellation {
                            unmountCleanups += 1
                        }
                    }

                    button {
                        onClick = { count += 1 }
                        +text
                    }
                }

                private fun ChildrenBuilder.OuterCounter(label: String) {
                    InnerCounter(label)
                }

                val Page: FC<PageProps> = Memoized { props ->
                    if (props.show) Heading(props.title)
                    Footer(props.title)
                }

                val CounterCard: FC<PageProps> = FC { props ->
                    OuterCounter(props.title)
                }

                @JsExport
                fun pageComponent(): Any = Page

                @JsExport
                fun counterComponent(): Any = CounterCard

                @JsExport
                fun footerRenderCount(): Int = footerRenders

                @JsExport
                fun recordedEffects(): String = effectHistory

                @JsExport
                fun cleanupCount(): Int = unmountCleanups
                """,
                "lib",
            )
        }.run(":app:compileDevelopmentExecutableKotlinJs")

        val mjsText = dir.walkTopDown().first { it.name.endsWith("-app.mjs") }.readText()
        assertFalse(
            "getLocalDelegateReference" in mjsText,
            "Expected no getLocalDelegateReference in app.mjs:\n$mjsText"
        )
        assertFalse("defaultSeparator\$ref" in mjsText, "Expected ::defaultSeparator eta-expanded in IR:\n$mjsText")
        assertFalse("format\$ref" in mjsText, "Expected formatter::format eta-expanded in IR:\n$mjsText")

        val output = runWithReactCompiler(
            "app",
            """
            const Page = m.pageComponent();
            const CounterCard = m.counterComponent();
            const container = document.createElement('div');
            document.body.appendChild(container);
            const root = createRoot(container);
            const steps = [];
            for (const props of [
              { show: true, title: 'A', unrelated: 1 },
              { show: false, title: 'B', unrelated: 1 },
              { show: true, title: 'B', unrelated: 1 },
            ]) {
              await act(async () => {
                root.render(React.createElement(Page, props));
              });
              steps.push(container.innerHTML);
            }
            const footerBeforeUnrelated = m.footerRenderCount();
            await act(async () => {
              root.render(React.createElement(Page, { show: true, title: 'B', unrelated: 2 }));
            });
            steps.push(container.innerHTML);
            const footerAfterUnrelated = m.footerRenderCount();

            const counterStates = [];
            await act(async () => {
              root.render(React.createElement(CounterCard, { show: true, title: 'clicks', unrelated: 0 }));
            });
            counterStates.push(container.innerHTML);
            await act(async () => {
              container.querySelector('button').click();
            });
            counterStates.push(container.innerHTML);
            const cleanupsBeforeUnmount = m.cleanupCount();

            await act(async () => root.unmount());
            const effects = m.recordedEffects();
            const cleanupsAfterUnmount = m.cleanupCount();
            console.log(JSON.stringify({ compiled, consoleErrors, steps, footerBeforeUnrelated, footerAfterUnrelated, counterStates, effects, cleanupsBeforeUnmount, cleanupsAfterUnmount }));
            """.trimIndent(),
        )

        assertTrue("Page\$lambda" in output, "Expected Page component compiled by React Compiler in:\n$output")
        assertTrue("useInnerCounter" in output, "Expected InnerCounter hook helper compiled by React Compiler in:\n$output")
        assertTrue("useOuterCounter" in output, "Expected OuterCounter transitive hook helper compiled by React Compiler in:\n$output")
        assertFalse(""""Heading"""" in output, "Expected non-hook Heading helper not compiled with its own cache in:\n$output")
        assertFalse(""""Footer"""" in output, "Expected non-hook Footer helper not compiled with its own cache in:\n$output")
        assertTrue(
            """"consoleErrors":[]""" in output,
            "Expected no React console errors, effect return warnings, or duplicate key warnings in:\n$output"
        )
        assertTrue(
            """"steps":["<span>A</span><b>A</b>","<b>B</b>","<span>B</span><b>B</b>","<span>B</span><b>B</b>"]""" in output,
            "Expected conditional helper not to corrupt sibling helper cache across renders in:\n$output",
        )
        assertTrue(
            """"footerBeforeUnrelated":2,"footerAfterUnrelated":2""" in output,
            "Expected caller's React Compiler cache to skip Footer(props.title) when title is unchanged in:\n$output",
        )
        assertTrue(
            """"counterStates":["<button>clicks:0</button>","<button>clicks:1</button>"]""" in output,
            "Expected hook-calling lowered helper to preserve and update state in React 19 in:\n$output",
        )
        assertTrue(
            """"effects":"clicks=0#1<none;clicks=1#2<clicks0;"""" in output,
            "Expected lowered synchronous useEffect and useRefValue delegate to track commits across state updates in:\n$output",
        )
        assertTrue(
            """"cleanupsBeforeUnmount":0,"cleanupsAfterUnmount":1""" in output,
            "Expected lowered suspending useEffectOnce with awaitCancellation to run cleanup on unmount in:\n$output",
        )
    }

    @Test
    fun `renders loop children as static children without key warnings in React 19`() {
        TestProject(dir, realReact = true).apply {
            module(
                "app",
                """
                @file:OptIn(kotlin.js.ExperimentalJsExport::class)
                @file:Suppress("REACT_ONLY_EXPORT_COMPONENTS")

                package app

                import react.*

                external interface ListProps : Props {
                    var items: Array<String>
                }

                private val ul: ElementType<FragmentProps> = "ul".unsafeCast<ElementType<FragmentProps>>()
                private val li: ElementType<FragmentProps> = "li".unsafeCast<ElementType<FragmentProps>>()

                private fun ChildrenBuilder.Items(items: Array<String>) {
                    for (item in items) li { +item }
                }

                private fun ChildrenBuilder.Row(item: String) {
                    li { +item }
                }

                private fun ChildrenBuilder.Cell(item: String) {
                    if (item == "b") li { +"B" } else li { +item }
                }

                val OnlyLoop: FC<ListProps> = FC { props ->
                    ul {
                        for (item in props.items) li { +item }
                    }
                }

                val LoopWithSiblings: FC<ListProps> = FC { props ->
                    ul {
                        li { +"first" }
                        for (item in props.items) li { +item }
                        li { +"last" }
                    }
                }

                val NestedLoops: FC<ListProps> = FC { props ->
                    ul {
                        if (props.items.size > 1) {
                            repeat(props.items.size) { index -> li { +props.items[index] } }
                        }
                        props.items.takeIf { it.isNotEmpty() }?.let { items ->
                            for (item in items) li { +item }
                        }
                    }
                }

                val RootLoop: FC<ListProps> = FC { props ->
                    for (item in props.items) li { +item }
                }

                val HelperLoop: FC<ListProps> = FC { props ->
                    ul { Items(props.items) }
                }

                val HelperInLoop: FC<ListProps> = FC { props ->
                    ul {
                        for (item in props.items) Cell(item)
                    }
                }

                val HelperTwice: FC<ListProps> = FC { props ->
                    ul {
                        Row(props.items.first())
                        Row(props.items.last())
                    }
                }

                @JsExport
                fun listComponents(): Array<Any> =
                    arrayOf(OnlyLoop, LoopWithSiblings, NestedLoops, RootLoop, HelperLoop, HelperInLoop, HelperTwice)
                """,
            )
        }.run(":app:compileDevelopmentExecutableKotlinJs")

        val output = runWithReactCompiler(
            "app",
            """
            const html = [];
            for (const component of m.listComponents()) {
              const container = document.createElement('div');
              document.body.appendChild(container);
              const root = createRoot(container);
              for (const items of [['a', 'b'], ['a', 'b', 'c']]) {
                await act(async () => {
                  root.render(React.createElement(component, { items }));
                });
              }
              html.push(container.innerHTML);
              await act(async () => root.unmount());
            }
            console.log(JSON.stringify({ compiled, consoleErrors, html }));
            """.trimIndent(),
        )

        for (name in listOf("OnlyLoop", "LoopWithSiblings", "NestedLoops", "RootLoop", "HelperLoop", "HelperInLoop", "HelperTwice")) {
            assertTrue("$name\$lambda" in output, "Expected $name compiled by React Compiler in:\n$output")
        }
        assertTrue(""""consoleErrors":[]""" in output, "Expected no key warnings for loop or helper children in:\n$output")
        assertTrue(
            """"html":[""" +
                """"<ul><li>a</li><li>b</li><li>c</li></ul>",""" +
                """"<ul><li>first</li><li>a</li><li>b</li><li>c</li><li>last</li></ul>",""" +
                """"<ul><li>a</li><li>b</li><li>c</li><li>a</li><li>b</li><li>c</li></ul>",""" +
                """"<li>a</li><li>b</li><li>c</li>",""" +
                """"<ul><li>a</li><li>b</li><li>c</li></ul>",""" +
                """"<ul><li>a</li><li>B</li><li>c</li></ul>",""" +
                """"<ul><li>a</li><li>c</li></ul>"]""" in output,
            "Expected every loop child rendered in order in:\n$output",
        )
    }

    @Test
    fun `keys children from different files apart when they share a line and column in React 19`() {
        assertCrossFileChildrenKeyedApart(seskar = false)
    }

    @Test
    fun `keys children from different files apart when Seskar keys them by line and column in React 19`() {
        assertCrossFileChildrenKeyedApart(seskar = true)
    }

    @Test
    fun `runs suspend lambdas passed to custom hooks that capture a ref in React 19`() {
        TestProject(dir, realReact = true).apply {
            module(
                "app",
                """
                @file:OptIn(kotlin.js.ExperimentalJsExport::class)
                @file:Suppress("REACT_ONLY_EXPORT_COMPONENTS")

                package app

                import kotlinx.coroutines.CoroutineScope
                import kotlinx.coroutines.CoroutineStart
                import kotlinx.coroutines.isActive
                import kotlinx.coroutines.launch
                import react.*

                private val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()

                fun useAsyncEffect(key: Any?, block: suspend () -> Unit) {
                    useEffect(key) { block() }
                }

                fun useKeyedEffect(key: String, block: suspend (String) -> Unit) {
                    useEffect(key) { block(key) }
                }

                fun useLaunch(key: Any?, block: suspend CoroutineScope.() -> Unit) {
                    useEffect(key) { launch(start = CoroutineStart.UNDISPATCHED, block = block) }
                }

                external interface PageProps : Props {
                    var title: String
                }

                @JsExport
                fun page(): Any = Page
                val Page: FC<PageProps> = FC { props ->
                    val seen = useRef("")
                    useAsyncEffect(props.title) {
                        seen.current = props.title
                        console.log("async:" + seen.current)
                    }
                    useKeyedEffect(props.title) { key -> console.log("keyed:" + key + ":" + seen.current) }
                    useLaunch(props.title) { console.log("launched:" + isActive + ":" + seen.current) }
                    div { +props.title }
                }
                """,
            )
        }.run(":app:compileDevelopmentExecutableKotlinJs")

        val output = runWithReactCompiler(
            "app",
            """
            const container = document.createElement('div');
            document.body.appendChild(container);
            const root = createRoot(container);
            await act(async () => {
              root.render(React.createElement(m.page(), { title: 'Hello' }));
            });
            console.log(JSON.stringify({ compiled, consoleErrors, html: container.innerHTML }));
            """.trimIndent(),
        )

        assertTrue(""""compiled":["useAsyncEffect","useKeyedEffect","useLaunch","Page${'$'}lambda"]""" in output, "Expected all compiled in:\n$output")
        assertTrue("async:Hello" in output, "Expected the ref written by the suspend lambda in:\n$output")
        assertTrue("keyed:Hello:Hello" in output, "Expected the suspend lambda called with its parameter in:\n$output")
        assertTrue("launched:true:Hello" in output, "Expected the suspend lambda started by launch in:\n$output")
        assertTrue(""""consoleErrors":[]""" in output, "Expected no React errors in:\n$output")
    }

    private fun assertCrossFileChildrenKeyedApart(seskar: Boolean) {
        TestProject(dir, realReact = true, seskar = seskar).apply {
            // `b` in Panel and `i` in Page both start at line 14, column 8 of their files: the plugin keys them by it,
            // or, with Seskar, Seskar does.
            module(
                "lib",
                """
                package lib

                import react.*

                private val div: ElementType<FragmentProps> = "div".unsafeCast<ElementType<FragmentProps>>()
                private val b: ElementType<FragmentProps> = "b".unsafeCast<ElementType<FragmentProps>>()

                external interface PanelProps : PropsWithChildren {
                    var unused: Int
                }

                val Panel: FC<PanelProps> = FC { props ->
                    div {
                        +props.children
                        b { +"own" }
                    }
                }
                """,
            )
            module(
                "app",
                """
                @file:OptIn(kotlin.js.ExperimentalJsExport::class)
                @file:Suppress("REACT_ONLY_EXPORT_COMPONENTS")

                package app

                import lib.Panel
                import react.*

                private val i: ElementType<FragmentProps> = "i".unsafeCast<ElementType<FragmentProps>>()

                @JsExport
                fun page(): Any = Page
                val Page: FC<Props> = FC {
                    Panel {
                        i { +"forwarded" }
                    }
                }
                """,
                "lib",
            )
        }.run(":app:compileDevelopmentExecutableKotlinJs")

        val output = runWithReactCompiler(
            "app",
            """
            const container = document.createElement('div');
            document.body.appendChild(container);
            const root = createRoot(container);
            await act(async () => {
              root.render(React.createElement(m.page(), {}));
            });
            console.log(JSON.stringify({ compiled, consoleErrors, html: container.innerHTML }));
            """.trimIndent(),
        )

        assertTrue(""""consoleErrors":[]""" in output, "Expected no duplicate key warning in:\n$output")
        assertTrue(""""html":"<div><i>forwarded</i><b>own</b></div>"""" in output, "Expected both children rendered in:\n$output")
    }

    @Test
    fun `lets effects write to values React does not own in React 19`() {
        TestProject(dir, realReact = true).apply {
            module(
                "app",
                """
                @file:OptIn(kotlin.js.ExperimentalJsExport::class)
                @file:Suppress("REACT_ONLY_EXPORT_COMPONENTS")

                package app

                import com.github.porotkin.reactcompiler.Mutable
                import react.*

                @Mutable
                open class Counter {
                    var count = 0
                }

                class Clicks : Counter()

                external interface CounterProps : Props {
                    var counter: Counter
                    var holder: RefObject<Counter>
                    var value: Int
                }

                fun useSyncCount(counter: Counter, value: Int) {
                    useEffect(value) {
                        counter.count = value
                    }
                }

                fun useHold(holder: RefObject<Counter>, counter: Counter) {
                    useEffect(counter) {
                        holder.current = counter
                    }
                }

                fun useAddOverOne(counter: Counter, value: Int) {
                    useEffect(value) {
                        try {
                            counter.count += value.takeIf { it > 1 } ?: 0
                        } catch (_: Throwable) {
                        }
                    }
                }

                val Shown: FC<CounterProps> = FC { props ->
                    val clicks = useMemo { Clicks() }
                    useSyncCount(props.counter, props.value)
                    useAddOverOne(props.counter, props.value)
                    useHold(props.holder, clicks)
                }

                @JsExport
                fun shown(): Any = Shown

                @JsExport
                fun counter(): Any = Counter()

                @JsExport
                fun countOf(counter: Any): Int = counter.unsafeCast<Counter>().count
                """,
            )
        }.run(":app:compileDevelopmentExecutableKotlinJs")

        val output = runWithReactCompiler(
            "app",
            """
            const container = document.createElement('div');
            document.body.appendChild(container);
            const root = createRoot(container);
            const counter = m.counter();
            const holder = { current: null };
            const counts = [];
            for (const value of [2, 5]) {
              await act(async () => {
                root.render(React.createElement(m.shown(), { counter, holder, value }));
                await new Promise((resolve) => setTimeout(resolve, 0));
              });
              counts.push(m.countOf(counter));
            }
            counts.push(holder.current === null ? -1 : m.countOf(holder.current));
            console.log(JSON.stringify({ compiled, consoleErrors, counts }));
            """.trimIndent(),
        )

        for (name in listOf("useSyncCount", "useAddOverOne", "useHold", "Shown\$lambda")) {
            assertTrue(name in output, "Expected $name compiled by React Compiler in:\n$output")
        }
        assertTrue(""""consoleErrors":[]""" in output, "Expected no console errors in:\n$output")
        // The synchronous effect writes `value`, the one with `try` (a coroutine) adds it when over 1, the ref gets `clicks`.
        assertTrue(""""counts":[4,10,0]""" in output, "Expected the writes made by the effects in:\n$output")
    }

    @Test
    fun `reads only the receivers of callback writes to mutable values through mutableValue`() {
        TestProject(dir, realReact = true).apply {
            module(
                "app",
                """
                package app

                import com.github.porotkin.reactcompiler.Mutable
                import react.*

                @Mutable
                class Counter {
                    var count = 0
                }

                class Plain {
                    var count = 0
                }

                fun useCounters(counter: Counter, plain: Plain): Counter {
                    counter.count = 1
                    useEffect(plain) {
                        plain.count = 2
                    }
                    useEffect(counter) {
                        counter.count = 3
                    }
                    return useMemo(counter) { Counter().apply { count = 4 } }
                }
                """,
                // The render-time write would fail the build; it is here to show it is left as it is.
                configuration = """reactCompiler { lint { off("REACT_IMMUTABILITY") } }""",
            )
        }.run(":app:compileDevelopmentExecutableKotlinJs")

        val mjs = dir.walkTopDown().first { it.name.endsWith("-app.mjs") }.readText()
        val writes = Regex("""(\S+)\.count_\w+ = (\d)""").findAll(mjs).associate { it.groupValues[2] to it.groupValues[1] }
        assertEquals(mapOf("1" to "counter", "2" to "plain"), writes.filterKeys { it == "1" || it == "2" }, "Expected render and plain writes left as they are in:\n$mjs")
        assertFalse(writes.getValue("4").startsWith("mutableValue"), "Expected the write to a counter useMemo creates left as it is in:\n$mjs")
        assertTrue(Regex("""mutableValue\w*\(counter\)""") in mjs, "Expected the effect's write to read counter through mutableValue in:\n$mjs")
    }

    /**
     * Copies the compiled `.mjs` bundle for [module] into a working directory under `testNodeDir`, compiles the
     * module with `babel-plugin-react-compiler` (`annotation` mode, React 19 target), and runs [script] in Node with
     * `React`, `act`, `createRoot`, `document`, `compiled` (names of compiled functions), `consoleErrors`, and `m`.
     */
    private fun runWithReactCompiler(
        module: String,
        script: String,
    ): String {
        val mjsFile = dir.walkTopDown().first { it.name.endsWith("-$module.mjs") }
        val testNodeDir = File(System.getProperty("testNodeDir"))
        val workDir = File(testNodeDir, "runs/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            mjsFile.parentFile.listFiles { f -> f.name.endsWith(".mjs") }.orEmpty().forEach { file ->
                file.copyTo(File(workDir, file.name), overwrite = true)
            }
            val runnerFile = File(workDir, "run-react-test.mjs")
            runnerFile.writeText(
                """
                import fs from 'node:fs';
                import * as babel from '@babel/core';
                import ReactCompilerPlugin from 'babel-plugin-react-compiler';
                import { JSDOM } from 'jsdom';
                import * as React from 'react';
                import { act } from 'react';
                import { createRoot } from 'react-dom/client';

                function kotlinReactPrepass({ types: t }) {
                  return {
                    visitor: {
                      Program(programPath) {
                        for (const name of Object.keys(programPath.scope.bindings)) {
                          if (/^(?:use[A-Z0-9].*\${'$'}lambda|[A-Z].*\${'$'}lambda\${'$'})/.test(name)) {
                            programPath.scope.rename(name, '_' + name);
                          }
                        }
                      },
                      Function(fnPath) {
                        const body = fnPath.node.body;
                        if (!t.isBlockStatement(body)) return;
                        body.body = body.body.filter((stmt) => {
                          if (
                            t.isExpressionStatement(stmt) &&
                            t.isStringLiteral(stmt.expression) &&
                            (stmt.expression.value === 'use memo' || stmt.expression.value === 'use no memo')
                          ) {
                            body.directives.push(t.directive(t.directiveLiteral(stmt.expression.value)));
                            return false;
                          }
                          return true;
                        });
                        fnPath.traverse({
                          Function(inner) {
                            inner.skip();
                          },
                          VariableDeclaration(varPath) {
                            if (varPath.node.kind === 'var') {
                              varPath.node.kind = 'let';
                            }
                          },
                        });
                      },
                    },
                  };
                }

                const compiled = [];
                const targetPath = new URL('./${mjsFile.name}', import.meta.url);
                const source = fs.readFileSync(targetPath, 'utf8');
                const normalized = babel.transformSync(source, {
                  filename: '${mjsFile.name}',
                  sourceType: 'module',
                  configFile: false,
                  babelrc: false,
                  plugins: [kotlinReactPrepass],
                });
                const transformed = babel.transformSync(normalized.code, {
                  filename: '${mjsFile.name}',
                  sourceType: 'module',
                  configFile: false,
                  babelrc: false,
                  plugins: [
                    [
                      ReactCompilerPlugin,
                      {
                        compilationMode: 'annotation',
                        target: '19',
                        panicThreshold: 'all_errors',
                        environment: {
                          moduleTypeProvider(moduleName) {
                            const jsxFunction = {
                              kind: 'function',
                              positionalParams: ['read', 'freeze', 'read'],
                              restParam: null,
                              calleeEffect: 'read',
                              returnType: { kind: 'type', name: 'Any' },
                              returnValueKind: 'frozen',
                            };
                            if (moduleName === 'react/jsx-runtime') {
                              return { kind: 'object', properties: { jsx: jsxFunction, jsxs: jsxFunction } };
                            }
                            if (moduleName.endsWith('/react/jsx/runtime/jsx.mjs')) {
                              return { kind: 'object', properties: { '*': jsxFunction } };
                            }
                            // `mutableValue()`, alone in its file (per-file output) and in the annotations module
                            if (moduleName.endsWith('/reactcompiler/MutableValue.mjs') || moduleName.endsWith('_reactcompiler_annotations.mjs')) {
                              const mutableValue = {
                                kind: 'function',
                                positionalParams: ['read'],
                                restParam: null,
                                calleeEffect: 'read',
                                returnType: { kind: 'type', name: 'Any' },
                                returnValueKind: 'global',
                              };
                              return { kind: 'object', properties: { '*': mutableValue } };
                            }
                            return null;
                          },
                        },
                        logger: {
                          logEvent(_filename, event) {
                            if (event.kind === 'CompileSuccess') {
                              compiled.push(event.fnLoc?.identifierName ?? event.fnName ?? '<anonymous>');
                            }
                          },
                        },
                      },
                    ],
                  ],
                });
                fs.writeFileSync(targetPath, transformed.code, 'utf8');

                const dom = new JSDOM('<!doctype html><html><body></body></html>');
                globalThis.window = dom.window;
                globalThis.document = dom.window.document;
                globalThis.IS_REACT_ACT_ENVIRONMENT = true;

                const consoleErrors = [];
                const originalError = console.error;
                console.error = (...args) => {
                  consoleErrors.push(args.map(String).join(' '));
                  originalError(...args);
                };

                const m = await import('./${mjsFile.name}');
                $script
                """.trimIndent(),
            )
            val process = ProcessBuilder("node", runnerFile.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            assertEquals(0, process.waitFor(), "Expected Node + React Compiler execution to succeed, got:\n$output")
            return output
        } finally {
            workDir.deleteRecursively()
        }
    }
}
