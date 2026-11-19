# Kotlin React Compiler

[![Test](https://github.com/porotkin/kotlin-react-compiler/actions/workflows/test.yml/badge.svg)](https://github.com/porotkin/kotlin-react-compiler/actions/workflows/test.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.porotkin.reactcompiler/compiler-plugin)](https://central.sonatype.com/artifact/io.github.porotkin.reactcompiler/compiler-plugin)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.porotkin.kotlin-react-compiler)](https://plugins.gradle.org/plugin/io.github.porotkin.kotlin-react-compiler)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.21-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![License](https://img.shields.io/github/license/porotkin/kotlin-react-compiler)](LICENSE)

[React Compiler](https://react.dev/learn/react-compiler) for `kotlin-react`. A Kotlin/JS compiler plugin that makes
your components and hooks memoizable, and checks them against the [Rules of React](#rules-of-react) at compile time.

How it works under the hood: [docs/how-it-works.md](docs/how-it-works.md).

## Setup

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.21"
    id("io.github.porotkin.kotlin-react-compiler") version "1.0.9"
}
```

Then run React Compiler over the output with
[vite-plugin-react-kotlinjs](https://github.com/porotkin/vite-plugin-react-kotlinjs) (a version after 2.1.1).

- Use the Kotlin version from the badge. The plugin warns when applied with another.
- Apply the plugin to the project that builds the executable too, not only to library modules.

## What Gets Memoized

- `FC { }` components and the `ChildrenBuilder` helper functions they call;
- custom `use*` hooks;
- `useMemo`, `useCallback`, `useEffect` and the other effect hooks;
- `by useState()`, `by useRefValue()` and `by useLatest()` locals.

Effects and callbacks may write to DOM nodes, refs, preact `Signal`s, and instances of classes annotated
`@com.github.porotkin.reactcompiler.Mutable`.

Not memoized:

- hooks that spread their dependencies (`useMemo(*deps) { }`);
- components that use `ChildrenBuilder` in a way the plugin cannot rewrite, such as passing it to another function.
  They are reported as `REACT_UNSUPPORTED_SYNTAX`.

In development, React may warn about `key` when an element's key changes while its props stay the same. Production
builds are not affected.

## Rules of React

React Compiler memoizes only components and hooks that follow the [Rules of React](https://react.dev/reference/rules),
and may miscompile the rest. The plugin reports violations as compiler diagnostics, named after the
[`eslint-plugin-react-hooks`](https://react.dev/reference/eslint-plugin-react-hooks) rules and with the levels of its
`recommended` preset.

| Diagnostic | Level | Reported |
|---|---|---|
| [`REACT_RULES_OF_HOOKS`](https://react.dev/reference/eslint-plugin-react-hooks/lints/rules-of-hooks) | error | A hook called conditionally, in a loop, after an early return, inside `try`, in a nested or suspend function, or outside components and hooks. `use` may be called conditionally. |
| [`REACT_EXHAUSTIVE_DEPS`](https://react.dev/reference/eslint-plugin-react-hooks/lints/exhaustive-deps) | warning | A hook with `vararg dependencies`, or a `use…EffectOnce`, whose callback reads a component value it does not list. For `useMemo` and `useCallback`, also a listed value the callback does not read. A value class counts as its underlying property. |
| [`REACT_HOOKS_AS_VALUES`](https://react.dev/reference/rules/react-calls-components-and-hooks#never-pass-around-hooks-as-regular-values) | error | `::useValue`; a function-typed property, parameter or variable named `use…`; a member or local `use…` function; any other `use…` value that a component or hook reads. |
| [`REACT_REFS`](https://react.dev/reference/eslint-plugin-react-hooks/lints/refs) | error | `ref.current`, or a `by useRefValue()` / `by useLatest()` local, read or written during render; `ref::current`. `if (ref.current == null) ref.current = …` may initialize a ref. |
| [`REACT_IMMUTABILITY`](https://react.dev/reference/eslint-plugin-react-hooks/lints/immutability) | error | Props or state changed anywhere: a property assigned, or a mutable collection or array modified. During render, also a hook argument, a hook result, or a local already passed to a hook or an element. |
| [`REACT_GLOBALS`](https://react.dev/reference/eslint-plugin-react-hooks/lints/globals) | error | A top-level or `object` property assigned during render, or a collection it holds changed. |
| [`REACT_PURITY`](https://react.dev/reference/eslint-plugin-react-hooks/lints/purity) | error | `Random`, `Date()`, `Date.now()`, `Clock.now()`, `performance.now()`, `crypto.randomUUID()`, `Uuid.random()`, or `random()` / `shuffled()` of a collection, called during render. |
| [`REACT_SET_STATE_IN_RENDER`](https://react.dev/reference/eslint-plugin-react-hooks/lints/set-state-in-render) | error | A state setter called, or a `by useState()` local assigned, on every render. Setting state under a condition, to derive it from props, is allowed. |
| [`REACT_SET_STATE_IN_EFFECT`](https://react.dev/reference/eslint-plugin-react-hooks/lints/set-state-in-effect) | error | State set as soon as `useEffect`, `useLayoutEffect` or `useInsertionEffect` runs. Setting it after a suspension, in a callback, or from a ref's value is allowed. |
| [`REACT_STATIC_COMPONENTS`](https://react.dev/reference/eslint-plugin-react-hooks/lints/static-components) | error | `FC { }`, a `@ComponentWrapper` function, `memo()`, `forwardRef()` or `lazy()` called during render. |
| [`REACT_ERROR_BOUNDARIES`](https://react.dev/reference/eslint-plugin-react-hooks/lints/error-boundaries) | error | An element created inside `try`. |
| [`REACT_USE_MEMO`](https://react.dev/reference/eslint-plugin-react-hooks/lints/use-memo) | error | `useMemo` whose callback returns `Unit`, or whose value is not used. |
| [`REACT_UNSUPPORTED_SYNTAX`](https://react.dev/reference/eslint-plugin-react-hooks/lints/unsupported-syntax) | warning | A component the plugin cannot lower, reported on the statement that stops it. Not reported for a component that opts out with `"use no memo"`. |
| `REACT_ONLY_EXPORT_COMPONENTS` | warning | In a file that exports a component (its first exported, capitalized `FC` property without a receiver), every other top-level declaration that is not `private`, external or a type alias. Fast Refresh keeps a component's state only if its module exports nothing else. This is `only-export-components` of `eslint-plugin-react-refresh`, not a Rule of React. Test compilations are not checked. |

See [how the linter defines components, hooks and render](docs/how-it-works.md#terms). It does not look into called
functions, so React Compiler may still find more: watch its `CompileError` events through the `logger` option of
vite-plugin-react-kotlinjs.

### Levels

```kotlin
reactCompiler {
    lint {
        error("REACT_EXHAUSTIVE_DEPS", "REACT_ONLY_EXPORT_COMPONENTS")
        warning("REACT_SET_STATE_IN_EFFECT")
        off("REACT_PURITY")
    }
}
```

`all(ReactLintLevel.WARNING)` sets every rule to one level, which helps when adopting the linter in an existing code
base; calls after it still apply. `enabled = false` turns the linter off. Kotlin prints no warnings for a compilation
that has errors, so lint warnings appear once its errors are fixed.

### Suppressing

Put `@Suppress("REACT_…")` on any enclosing declaration: a local variable, function, property, class, or the file. To
suppress `REACT_EXHAUSTIVE_DEPS` for one hook, annotate its lambda:

```kotlin
useEffect(flightPart) @Suppress("REACT_EXHAUSTIVE_DEPS") {
    if (!visible) key = createKey()
}
```

## Opting Out

Start a component lambda, helper or `use*` function with `js("\"use no memo\"")` to have React Compiler skip it. Use
this where render reads values that change without changing identity, such as TanStack Table / Virtual instances
received from another hook.

An opted-out function is still checked for `REACT_RULES_OF_HOOKS`, `REACT_EXHAUSTIVE_DEPS`, `REACT_HOOKS_AS_VALUES`
and `REACT_USE_MEMO`, because React needs these rules with or without the compiler.

## Wrappers

The plugin adds the `annotations` artifact to every Kotlin/JS module it is applied to. Both the module that declares a
wrapper and every module that calls it must be compiled with the plugin.

### Component Wrappers

Annotate a function with `@com.github.porotkin.reactcompiler.ComponentWrapper` to have its call sites memoized. The
function must be final, non-overriding and return `FC`. It must take a `ChildrenBuilder.(props) -> Unit` render lambda
and only pass that lambda to `FC(render)` or to another wrapper.

### Element Wrappers

Annotate an `operator fun invoke` with `@com.github.porotkin.reactcompiler.ElementWrapper` to have its call sites
lowered to `jsx()`. The class must have a single public `ElementType` property, and `invoke` must:

- be final and non-overriding;
- take a `ChildrenBuilder`, prop parameters, and a `ChildrenBuilder.() -> Unit` block;
- only invoke the `ElementType` property, assigning each parameter to the writable prop of the same name on the
  element's `PropsWithChildren` type and calling `block()`.
