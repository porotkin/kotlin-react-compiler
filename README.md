# Kotlin React Compiler

[![Test](https://github.com/porotkin/kotlin-react-compiler/actions/workflows/test.yml/badge.svg)](https://github.com/porotkin/kotlin-react-compiler/actions/workflows/test.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.porotkin.reactcompiler/compiler-plugin)](https://central.sonatype.com/artifact/io.github.porotkin.reactcompiler/compiler-plugin)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.porotkin.kotlin-react-compiler)](https://plugins.gradle.org/plugin/io.github.porotkin.kotlin-react-compiler)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.21-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![License](https://img.shields.io/github/license/porotkin/kotlin-react-compiler)](LICENSE)

A Kotlin/JS IR compiler plugin and Gradle plugin that lowers `kotlin-react` components (`FC`) and hooks (`useMemo`, `useCallback`, custom `use*` hooks) into [React Compiler](https://react.dev/learn/react-compiler)-compatible JavaScript shapes.

## Setup

Apply the plugin to a Kotlin/JS project:

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.21"
    id("io.github.porotkin.kotlin-react-compiler") version "1.0.2"
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
for example) is left as it is, and React Compiler does not memoize it. Run the compile task with `--info` to see each
one, with the statement that stops the plugin.

In development, React may warn that `key` is not a prop, or that a props object with a `key` is spread into JSX, when
the key of an element changes while its other props stay the same: React Compiler caches the props object apart from
the `jsx()` call, and React's development build marks a props object once it has seen it with a key. Production builds
are not affected.

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
