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

                private fun ChildrenBuilder.Heading(title: String) {
                    span { +title }
                }

                @Suppress("REACT_GLOBALS")
                private fun ChildrenBuilder.Footer(title: String) {
                    footerRenders++
                    b { +title }
                }

                private fun ChildrenBuilder.InnerCounter(label: String) {
                    var count by useState(0)
                    val text = useMemo(label, count) { "${'$'}label:${'$'}count" }
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
                """,
                "lib",
            )
        }.run(":app:compileDevelopmentExecutableKotlinJs")

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

            await act(async () => root.unmount());
            console.log(JSON.stringify({ compiled, consoleErrors, steps, footerBeforeUnrelated, footerAfterUnrelated, counterStates }));
            """.trimIndent(),
        )

        assertTrue("Page\$lambda" in output, "Expected Page component compiled by React Compiler in:\n$output")
        assertTrue("useInnerCounter" in output, "Expected InnerCounter hook helper compiled by React Compiler in:\n$output")
        assertTrue("useOuterCounter" in output, "Expected OuterCounter transitive hook helper compiled by React Compiler in:\n$output")
        assertFalse(""""Heading"""" in output, "Expected non-hook Heading helper not compiled with its own cache in:\n$output")
        assertFalse(""""Footer"""" in output, "Expected non-hook Footer helper not compiled with its own cache in:\n$output")
        assertTrue(""""consoleErrors":[]""" in output, "Expected no React console errors or duplicate key warnings in:\n$output")
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
