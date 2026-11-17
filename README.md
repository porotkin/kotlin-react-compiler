# Kotlin React Compiler

[![Test](https://github.com/porotkin/kotlin-react-compiler/actions/workflows/test.yml/badge.svg)](https://github.com/porotkin/kotlin-react-compiler/actions/workflows/test.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.porotkin.reactcompiler/compiler-plugin)](https://central.sonatype.com/artifact/io.github.porotkin.reactcompiler/compiler-plugin)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.porotkin.kotlin-react-compiler)](https://plugins.gradle.org/plugin/io.github.porotkin.kotlin-react-compiler)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.21-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![License](https://img.shields.io/github/license/porotkin/kotlin-react-compiler)](LICENSE)

A Kotlin/JS compiler plugin and Gradle plugin that lowers `kotlin-react` components and hooks into JavaScript that
[React Compiler](https://react.dev/learn/react-compiler) can memoize, and checks them against the
[Rules of React](#rules-of-react).

## Setup

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.21"
    id("io.github.porotkin.kotlin-react-compiler") version "1.0.7"
}
```

- Built for one Kotlin version (see the badge). It warns when applied with another.
- Lowers only JS compilations with `kotlin-react` on the classpath. If that version lacks a declaration the plugin
  needs, the plugin warns and skips the module.
- React Compiler itself runs over the output in
  [vite-plugin-react-kotlinjs](https://github.com/porotkin/vite-plugin-react-kotlinjs).
- Passes `-Xir-generate-inline-anonymous-functions` to the JS target, because React Compiler accepts hook callbacks
  only as inline functions. The option takes effect where the JavaScript is generated, so apply the plugin to the
  project that builds the executable too.

## What It Lowers

- **Components (`FC`):** `FC { }` lambdas become `(props) -> ReactNode?` functions that start with `"use memo"`, and
  `ChildrenBuilder` calls become direct `jsx(type, attrs, key)` calls. An element without an explicit `key` gets
  Seskar's default key or `"@rdk/<file hash>/<line>_<col>"`. Elements added in a loop and nodes returned by helpers
  get no key. When a loop is among an element's children, the children are collected into one array and passed as
  static children (`jsxs`).

  ```kotlin
  val Title = FC<TitleProps> { props ->
      h1 { +props.title }
  }
  ```
  ```js
  function Title$lambda(props) {
    'use memo';
    var attrs = {};
    attrs.children = props.title;
    return jsx(h1, attrs, '@rdk/1ox2ifs/1_4');
  }
  ```
- **Component helpers:** `private fun ChildrenBuilder.Foo(...)` functions that return `Unit` and are called by a
  lowered component are lowered the same way. The lowered copy replaces the original, or is added as `Foo$lowered`
  while other code still calls the original. A helper that calls hooks is renamed `useFoo` and gets `"use memo"`,
  because React Compiler recognizes hooks only by name. Recursive helpers are not lowered.
- **`useMemo` / `useCallback`:** become `useMemoRaw(callback, dependencies)` / `useCallbackRaw(callback,
  dependencies)` with an inline callback. Dependencies other than plain variables are bound to locals first.

  ```kotlin
  useMemo(title) { title.uppercase() }
  ```
  ```js
  useMemoRaw(function () {
    return title.toUpperCase();
  }, [title])
  ```
- **Effect hooks:** `useEffect`, `useLayoutEffect`, `useInsertionEffect` and their `*Once` forms call React's own hook
  with an inline callback. An effect without suspend calls, `try`, or use of its `CoroutineScope` runs directly. Any
  other effect runs as a coroutine.
- **Writes to values React does not own:** React Compiler rejects a component or hook whose effect or callback writes
  to props, state, a hook argument or a hook result. In lambdas and local functions of a function that starts with
  `"use memo"`, the plugin rewrites property writes to a DOM node, a ref, a preact `Signal`, or an instance of a class
  annotated `@com.github.porotkin.reactcompiler.Mutable` (subclasses included). The value is read through
  `mutableValue()`, also when the write goes through getters (`element.style.color = …`). vite-plugin-react-kotlinjs
  types that function as returning a value from outside React, so React Compiler accepts the write in an effect or
  callback but still rejects it during render. Only values from the render scope are rewritten: a value the lambda
  creates itself is left as is.
- **`by` delegates:** a `by useState()` local becomes a value and a setter, like `val (count, setCount) = useState()`.
  A `by useRefValue()` or `by useLatest()` local becomes plain `.current` reads and writes, which React Compiler
  recognizes as ref accesses.
- **Callable references passed to hooks** (`useCallback(::save)`) are expanded into inline lambdas. A reference passed
  as a `KFunction` is kept, because the hook may read its metadata.
- **Suspend lambdas passed to hooks:** a suspend `useMemo` / `useCallback` callback, or a suspend lambda passed to a
  suspend-function parameter of a custom `use*` hook, is wrapped in a plain function that creates the lambda when
  the hook calls it. Without the wrapper, the lambda would be created during render, where React Compiler rejects a
  ref it captures. As a result, an `is` check against a suspend function type fails on the wrapped value. A lambda
  stored in a variable first is still created during render.
- **Custom hooks (`use*`):** top-level, non-inline, non-suspend `use*` functions get `"use memo"`.

Hook calls that spread their dependencies (`useMemo(*deps) { }`) are left as is, and so is any hook that makes such a
call: React Compiler rejects a dependency list that is not an array literal.

A component that uses its `ChildrenBuilder` in a way the plugin cannot rewrite (passing it to another function, for
example) is left as is, and React Compiler does not memoize it. It is reported as `REACT_UNSUPPORTED_SYNTAX`. With that
rule off, run the compile task with `--info` to see these components.

In development, React may warn that `key` is not a prop, or that a props object with a `key` is spread into JSX. This
happens when an element's key changes while its props stay the same, because React Compiler caches the props object
separately from the `jsx()` call. Production builds are not affected.

## Rules of React

React Compiler memoizes only components and hooks that follow the [Rules of React](https://react.dev/reference/rules).
It silently skips the rest or, if it cannot see the violation, compiles them into code that behaves differently. The
plugin checks the rules on the Kotlin code, in every module, and reports violations as compiler diagnostics. Each
diagnostic is named after the [`eslint-plugin-react-hooks`](https://react.dev/reference/eslint-plugin-react-hooks)
rule it follows and has the level that plugin's `recommended` preset gives that rule.

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

The remaining rules of the ESLint plugin have nothing to check in Kotlin. `config` and `gating` configure the Babel
plugin. `incompatible-library` corresponds to the `incompatibleHooks` option of vite-plugin-react-kotlinjs.
`component-hook-factories` is deprecated. `preserve-manual-memoization` is usually caused by incomplete dependencies,
which `REACT_EXHAUSTIVE_DEPS` reports.

Terms:

- A **hook** is a function named `use…`.
- A **component** is the lambda of `FC { }` or of a `@ComponentWrapper` call, or a function that takes a
  `ChildrenBuilder` or has a capitalized name. A hook, or a function that returns an `FC`, is not a component. A
  capitalized function is held to the rules about render only if it takes a `ChildrenBuilder` or `Props`, or returns
  `ReactNode`.
- Code runs **during render** in these places:
  - at the top level of a component or hook, including its branches and loops;
  - in element bodies (`div { }`);
  - in lambdas that run in place: scope functions (`let`, `run`, `apply`, `also`, `with`, `takeIf`, `takeUnless`),
    `buildList` and similar builders, `jso`, and, for the rules about render, any inline function and `useMemo`.

  Other lambdas and local functions run later. The Rules of Hooks treat them as nested functions, and the rules about
  render do not apply to them.
- A value is tracked as **props, state or a hook result** from where it is declared, through `val`s, properties,
  destructuring and scope functions. Tracking stops at a ref.

The linter follows the code only as written: it does not look into called functions. React Compiler still finds more,
so watch its `CompileError` events through the `logger` option of vite-plugin-react-kotlinjs.

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

To suppress a diagnostic, put `@Suppress("REACT_…")` on any enclosing declaration: a local variable, function,
property, class, or the file. `REACT_EXHAUSTIVE_DEPS` is reported on the callback, so you can suppress it for one hook
by annotating that hook's lambda:

```kotlin
useEffect(flightPart) @Suppress("REACT_EXHAUSTIVE_DEPS") {
    if (!visible) key = createKey()
}
```

## Opting out

React Compiler skips a component lambda, helper or `use*` function whose body starts with `js("\"use no memo\"")`,
even though the plugin adds `"use memo"` before it. Use this where render reads values that change without changing
identity, such as TanStack Table / Virtual instances received from another hook: React Compiler skips only the
functions that call those libraries' hooks directly.

An opted-out function is still checked for `REACT_RULES_OF_HOOKS`, `REACT_EXHAUSTIVE_DEPS`, `REACT_HOOKS_AS_VALUES`
and `REACT_USE_MEMO`, because React needs these rules with or without the compiler. The other rules skip it.

## Component wrappers

To have the call sites of a wrapper function lowered, annotate it with
`@com.github.porotkin.reactcompiler.ComponentWrapper`. The function must be final, non-overriding and return `FC`. It
must take a `ChildrenBuilder.(props) -> Unit` render lambda and only pass that lambda to `FC(render)` or to another
wrapper; the declaring module checks this contract. The plugin rewrites the wrapper to take an already-built
component, and each call site passes either a lowered lambda or, when the lambda cannot be lowered, `FC(lambda)`.

Both the declaring module and every calling module must be compiled with the plugin:

- `@ComponentWrapper` is an error-level `@RequiresOptIn` marker, which the plugin opts into.
- Calling a wrapper that lacks the plugin's `.klib` stamp fails the build. Wrappers compiled with plugin 1.0.2 or
  older have no stamp and must be recompiled.

The plugin adds the `annotations` artifact to every Kotlin/JS module it is applied to.

## Element wrappers

To have the call sites of an element wrapper lowered to `jsx()`, annotate its `operator fun invoke` with
`@com.github.porotkin.reactcompiler.ElementWrapper`. The class must have a single public `ElementType` property, and
`invoke` must:

- be final and non-overriding;
- take a `ChildrenBuilder`, prop parameters, and a `ChildrenBuilder.() -> Unit` block;
- only invoke the `ElementType` property, assigning each parameter to the writable prop of the same name on the
  element's `PropsWithChildren` type and calling `block()`.

The declaring module verifies these requirements and stamps `invoke` in `.klib` metadata. Other modules lower only
stamped wrappers, so wrappers from modules compiled without the plugin are never lowered. Call sites compiled without
the plugin run the `invoke` body as is.
