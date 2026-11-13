# Kotlin React Compiler

[![Test](https://github.com/porotkin/kotlin-react-compiler/actions/workflows/test.yml/badge.svg)](https://github.com/porotkin/kotlin-react-compiler/actions/workflows/test.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.porotkin.reactcompiler/compiler-plugin)](https://central.sonatype.com/artifact/io.github.porotkin.reactcompiler/compiler-plugin)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.porotkin.kotlin-react-compiler)](https://plugins.gradle.org/plugin/io.github.porotkin.kotlin-react-compiler)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.21-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![License](https://img.shields.io/github/license/porotkin/kotlin-react-compiler)](LICENSE)

A Kotlin/JS IR compiler plugin and Gradle plugin that lowers `kotlin-react` components (`FC`) and hooks (`useMemo`, `useCallback`, custom `use*` hooks) into [React Compiler](https://react.dev/learn/react-compiler)-compatible JavaScript shapes, and checks them against the [Rules of React](#rules-of-react).

## Setup

Apply the plugin to a Kotlin/JS project:

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.21"
    id("io.github.porotkin.kotlin-react-compiler") version "1.0.5"
}
```

The plugin is built for one Kotlin version (see the badge) and warns when applied with another. It only lowers JS
compilations that have `kotlin-react` on the classpath.

React Compiler itself runs over the generated JavaScript in
[vite-plugin-react-kotlinjs](https://github.com/porotkin/vite-plugin-react-kotlinjs), which first rewrites what
Kotlin/JS emits around these shapes: `var` locals, default arguments, and a `"use memo"` directive pushed below the
file's property initialization call.

The Gradle plugin also passes `-Xir-generate-inline-anonymous-functions` to the JS target, so a lambda that captures
values is emitted where it is written: React Compiler takes a `useMemo` / `useCallback` callback only as an inline
function. The option counts where the JavaScript is generated, so apply the plugin to the project that builds the
executable too.

## What It Lowers

- **Component lowering (`FC`):** Rewrites `FC { ... }` component lambdas from `($this$FC, props) -> Unit` into `(props) -> ReactNode?` functions that emit `"use memo"` at the start of the body, keep hook calls at the top level, and lower `ChildrenBuilder` element DSL calls into direct `jsx(type, attrs, key)` calls. The key is the element's explicit `key` when one is set, otherwise `"@rdk/<line>_<col>"` of the call site.

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
    return jsx(h1, attrs, '@rdk/1_4');
  }
  ```
- **Component helpers:** `private fun ChildrenBuilder.Foo(...)` functions returning `Unit` that a lowered component calls are lowered the same way when their bodies can be. The lowered copy drops the `ChildrenBuilder` receiver and replaces the original, or is added next to it as `Foo$lowered` while other code still calls the original. A lowered helper that calls hooks is renamed `useFoo`: React Compiler recognizes hooks only by name and would otherwise memoize the call and drop its hooks' state. Recursive helpers are not lowered.

  ```kotlin
  private fun ChildrenBuilder.Header(props: TitleProps) {
      val title = useLabel(props.title)
      h1 { +title }
  }
  ```
  ```js
  function useHeader(props) {
    'use memo';
    var title = useLabel(props.title);
    var attrs = {};
    attrs.children = title;
    return jsx(h1, attrs, '@rdk/2_4');
  }
  ```
- **Hook argument reordering (`useMemo` / `useCallback`):** Rewrites `kotlin-react`'s `(vararg dependencies, callback)` hook calls to `useMemoRaw(callback, dependencies)` / `useCallbackRaw(callback, dependencies)` in IR before Kotlin/JS lowers arguments into reversed evaluation-order temporaries or applies Seskar `@JsRaw` value-class dependency array wrappers. Dependencies other than plain variables are bound to locals first; calls spreading a vararg (`useMemo(*deps) { }`) are left as is.

  ```kotlin
  useMemo(title) { title.uppercase() }
  ```
  ```js
  useMemoRaw(function () {
    return title.toUpperCase();
  }, [title])
  ```
- **Custom hook directive injection (`use*`):** Prepends `js("\"use memo\"")` to top-level, non-inline, non-suspend `use*` functions so React Compiler compiles them in `annotation` mode and preserves top-level hook calls inside hoisted `$lambda` helpers. Hooks that call `useMemo(*deps)` / `useCallback(*deps)` are skipped, since React Compiler rejects a dependency list that is not an array literal.

  ```kotlin
  fun useLabel(title: String): String =
      useMemo(title) { title.uppercase() }
  ```
  ```js
  function useLabel(title) {
    'use memo';
    return useMemoRaw(function () {
      return title.toUpperCase();
    }, [title]);
  }
  ```

A component whose body uses its `ChildrenBuilder` in a way the plugin cannot rewrite (passing it to another function,
for example) is left as it is, and React Compiler does not memoize it. Each one is reported as a
`REACT_UNSUPPORTED_SYNTAX` warning, on the statement that stops the plugin; with that rule off, run the compile task
with `--info` to see them.

In development, React may warn that `key` is not a prop, or that a props object with a `key` is spread into JSX, when
the key of an element changes while its other props stay the same: React Compiler caches the props object apart from
the `jsx()` call, and React's development build marks a props object once it has seen it with a key. Production builds
are not affected.

## Rules of React

React Compiler memoizes a component or a hook only if it follows the [Rules of React](https://react.dev/reference/rules). One that breaks them
is left as it is, without a word, or, where the compiler cannot see the breach, compiled into something that behaves
differently. The plugin checks the rules while it compiles Kotlin and reports what breaks them as compiler
diagnostics on the Kotlin line: in every module, whether React Compiler gets to see the function or not.

The rules are React's own. Each diagnostic is named after the rule of
[`eslint-plugin-react-hooks`](https://react.dev/reference/eslint-plugin-react-hooks) it follows, and has the level
that plugin's `recommended` preset gives the rule:

| Diagnostic | Level | Rule | Reported |
|---|---|---|---|
| `REACT_RULES_OF_HOOKS` | error | [Rules of Hooks](https://react.dev/reference/rules/rules-of-hooks), [`rules-of-hooks`](https://react.dev/reference/eslint-plugin-react-hooks/lints/rules-of-hooks) | A hook called conditionally, in a loop, after an early return, inside `try`, in a nested or suspend function, or outside components and hooks. `use` may be called conditionally. |
| `REACT_EXHAUSTIVE_DEPS` | warning | [`exhaustive-deps`](https://react.dev/reference/eslint-plugin-react-hooks/lints/exhaustive-deps) | A hook with `vararg dependencies` whose callback reads a value of the component that is not among them; for `useMemo` and `useCallback`, also a dependency the callback does not read. |
| `REACT_HOOKS_AS_VALUES` | error | [Never pass around Hooks as regular values](https://react.dev/reference/rules/react-calls-components-and-hooks#never-pass-around-hooks-as-regular-values) | `::useValue`; a function-typed property, parameter or variable named `use…`; a `use…` function that is a member or local; any other value named `use…` that a component or hook reads. |
| `REACT_REFS` | error | [`refs`](https://react.dev/reference/eslint-plugin-react-hooks/lints/refs) | `ref.current`, or a local delegated to `useRefValue()`, read or written during render; `ref::current`. `if (ref.current == null) ref.current = …` may initialize a ref. |
| `REACT_IMMUTABILITY` | error | [Props and state are immutable](https://react.dev/reference/rules/components-and-hooks-must-be-pure#props-and-state-are-immutable), [`immutability`](https://react.dev/reference/eslint-plugin-react-hooks/lints/immutability) | A property assigned, or a mutable collection or array changed, on props or state anywhere, and during render on a hook argument, a value returned from a hook, or a local already passed to a hook or to an element. |
| `REACT_GLOBALS` | error | [Side effects must run outside of render](https://react.dev/reference/rules/components-and-hooks-must-be-pure#side-effects-must-run-outside-of-render), [`globals`](https://react.dev/reference/eslint-plugin-react-hooks/lints/globals) | A top-level or `object` property assigned, or a collection it holds changed, during render. |
| `REACT_PURITY` | error | [Components and Hooks must be idempotent](https://react.dev/reference/rules/components-and-hooks-must-be-pure#components-and-hooks-must-be-idempotent), [`purity`](https://react.dev/reference/eslint-plugin-react-hooks/lints/purity) | `Random`, `Date()`, `Date.now()`, `Clock.now()`, `performance.now()`, `crypto.randomUUID()`, `Uuid.random()`, `random()` and `shuffled()` of a collection, called during render. |
| `REACT_SET_STATE_IN_RENDER` | error | [`set-state-in-render`](https://react.dev/reference/eslint-plugin-react-hooks/lints/set-state-in-render) | A state setter called, or a `by useState()` local assigned, on every render. Setting state under a condition, to derive it from props, is allowed. |
| `REACT_SET_STATE_IN_EFFECT` | error | [`set-state-in-effect`](https://react.dev/reference/eslint-plugin-react-hooks/lints/set-state-in-effect) | State set as soon as `useEffect`, `useLayoutEffect` or `useInsertionEffect` runs: not after a suspension, not in a callback, not from the value of a ref. |
| `REACT_STATIC_COMPONENTS` | error | [`static-components`](https://react.dev/reference/eslint-plugin-react-hooks/lints/static-components) | `FC { }`, a `@ComponentWrapper` function, `memo()`, `forwardRef()` or `lazy()` called during render. |
| `REACT_ERROR_BOUNDARIES` | error | [`error-boundaries`](https://react.dev/reference/eslint-plugin-react-hooks/lints/error-boundaries) | An element created inside `try`. |
| `REACT_USE_MEMO` | error | [`use-memo`](https://react.dev/reference/eslint-plugin-react-hooks/lints/use-memo) | `useMemo` whose callback returns `Unit`, or whose value is not used. |
| `REACT_UNSUPPORTED_SYNTAX` | warning | [`unsupported-syntax`](https://react.dev/reference/eslint-plugin-react-hooks/lints/unsupported-syntax) | A component the plugin cannot lower, on the statement that stops it: React Compiler never sees the component. One that opts out with `"use no memo"` is not reported. |
| `REACT_ONLY_EXPORT_COMPONENTS` | warning | not a Rule of React: `only-export-components` of `eslint-plugin-react-refresh` | In a file whose first exported `FC` property is its component, every other top-level declaration that is not `private`, external or a type alias. Fast Refresh keeps the state of a component only if its module exports nothing else. Test compilations are not checked. |

The other rules of the ESLint plugin have nothing to check in Kotlin: `config` and `gating` are about options of the
Babel plugin, `incompatible-library` is the `incompatibleHooks` option of
[vite-plugin-react-kotlinjs](https://github.com/porotkin/vite-plugin-react-kotlinjs), `component-hook-factories` is
deprecated, and `preserve-manual-memoization` is React Compiler comparing its own memoization with the manual one
(incomplete dependencies, its usual cause, are `REACT_EXHAUSTIVE_DEPS`). "Never call component functions directly"
cannot be broken: an `FC` is not callable.

What the rules mean by their terms:

- A **hook** is a function named `use…`, as in React. A **component** is the lambda of `FC { }` or of a
  `@ComponentWrapper` call, a function that takes a `ChildrenBuilder`, or, as in React, a function whose name starts
  with a capital letter.
- Code runs **during render** at the top level of a component or hook, in the branches and loops there, in the bodies
  of its elements (`div { }`), and in the lambdas of functions that run them in place: `run`, `let`, `apply`, `also`,
  `with`, and, where the rules about render are concerned, any inline function and `useMemo`. Other lambdas and local
  functions run later: for the Rules of Hooks they are nested functions, and the rules about render do not apply to
  them.
- A value is known to be props, state or a hook result where it is declared, and through the `val`s, properties,
  destructuring and scope functions that read from it.

The linter follows the code as it is written. It does not look into the functions a component calls, and does not
follow a value through anything but the above, so React Compiler, which analyzes the generated JavaScript further, still
finds what the linter cannot: keep watching its `CompileError` events, as the `logger` option of
vite-plugin-react-kotlinjs lets you, to learn about a function it gave up on.

### Levels

```kotlin
reactCompiler {
    lint {
        error("REACT_EXHAUSTIVE_DEPS", "REACT_ONLY_EXPORT_COMPONENTS")
        warning("REACT_SET_STATE_IN_EFFECT")
        off("REACT_PURITY")
        // all(ReactLintLevel.WARNING) sets every rule, to adopt the linter in a code base that breaks the rules
        // enabled = false turns the linter off
    }
}
```

Kotlin prints no warnings for a compilation that has errors, so the warnings of the linter appear once its errors
are gone.

### Suppressing

A diagnostic is suppressed with `@Suppress` on any declaration around the code: a local variable, a function, a
property, a class, or the file.

```kotlin
@Suppress("REACT_REFS")
val Measured: FC<Props> = FC {
    // ...
}
```

`REACT_EXHAUSTIVE_DEPS` is reported on the callback, so that a hook can be suppressed alone, by annotating its lambda:

```kotlin
// `visible` is deliberately not a dependency: only a change of the flight part resets the key
useEffect(flightPart) @Suppress("REACT_EXHAUSTIVE_DEPS") {
    if (!visible) key = createKey()
}
```

A component or hook that opts out of React Compiler with `js("\"use no memo\"")` is still checked for
`REACT_RULES_OF_HOOKS`, `REACT_EXHAUSTIVE_DEPS`, `REACT_HOOKS_AS_VALUES` and `REACT_USE_MEMO`, which React needs
with or without the compiler. The other rules are not applied to it.

## Opting out

React Compiler leaves a component lambda, helper, or `use*` function whose body starts with `js("\"use no memo\"")`
as is: the opt-out wins over the `"use memo"` the plugin puts before it. Use it where render reads values that change without changing identity, such as
the table, rows, columns, or virtualizer of TanStack Table / Virtual received from another hook: the compiler only
skips functions that call those libraries' hooks themselves.

## Component wrappers

A final, non-overriding function returning `FC` that takes a `ChildrenBuilder.(props) -> Unit` render lambda and only
passes it to `FC(render)` (or to another wrapper) can opt in to lowering its call sites by annotating it with
`@com.github.porotkin.reactcompiler.ComponentWrapper`. The plugin adds the `annotations` artifact to every Kotlin/JS
module it is applied to.

The plugin rewrites the wrapper body so it takes an already-built component. Every call site then passes either a
lowered lambda or, when the lambda cannot be lowered, `FC(lambda)`, so runtime behavior is preserved. The contract is
checked in the declaring module (a wrapper that uses its lambda in any other way fails the build). Call sites in every
module must be compiled with the plugin: `@ComponentWrapper` is a `@RequiresOptIn` marker with `ERROR` level, and the
plugin opts in the compilations it is applied to, so a module without the plugin cannot call a wrapper. The declaring
module must be compiled with the plugin too: a rewritten wrapper carries the same `.klib` metadata stamp as a verified
element wrapper (below), and calling a wrapper without it fails the build. Wrappers compiled with plugin 1.0.2 or older
have no stamp and must be recompiled. Wrappers without the annotation are never rewritten.

## Element wrappers

A class with a single public `ElementType` property and a final, non-overriding member `operator fun invoke` taking
`ChildrenBuilder`, prop parameters, and a `ChildrenBuilder.() -> Unit` block can opt in to lowering its call sites to
`jsx()` by annotating `invoke` with `@com.github.porotkin.reactcompiler.ElementWrapper`. Call sites compiled without
the plugin run the `invoke` body as is.

The declaring module verifies at compile time that `invoke` is final and non-overriding, that every parameter matches a
writable property on the element's `PropsWithChildren` type, and that the body only invokes the `ElementType` property,
assigning each parameter to the matching prop and calling `block()`. Lowered call sites emit typed calls to the verified
`Props` setters (preserving `@JsName` and Kotlin property mangling).

For cross-module calls, the plugin records a synthetic `@kotlin.js.EagerInitialization` stamp on verified `invoke`
functions in `.klib` metadata (which user code cannot apply to a function because `@EagerInitialization` targets
properties). Downstream modules only trust external `@ElementWrapper` declarations carrying this stamp, so declarations
from modules compiled without the plugin are never lowered.
