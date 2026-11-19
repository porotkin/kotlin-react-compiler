# How It Works

The plugin rewrites `kotlin-react` code into JavaScript that [React Compiler](https://react.dev/learn/react-compiler)
can memoize, and checks it against the [Rules of React](https://react.dev/reference/rules).
[vite-plugin-react-kotlinjs](https://github.com/porotkin/vite-plugin-react-kotlinjs) then runs React Compiler over the
output.

## Compilation

- The plugin lowers only JS compilations with `kotlin-react` on the classpath. If that version lacks a declaration the
  plugin needs, the plugin warns and skips the module.
- The plugin emits no `js()` calls, which the Kotlin compiler parses one by one (a module with many elements took up
  to a third longer to compile). Instead, it marks a function to compile with `__krcUseMemo();`, which
  vite-plugin-react-kotlinjs turns into `"use memo"`, and starts props objects with `Object()`, which it turns into
  `{}` in compiled functions. This requires vite-plugin-react-kotlinjs after 2.1.1 since plugin 1.0.9.
- The plugin passes `-Xir-generate-inline-anonymous-functions` to the JS target, because React Compiler accepts hook
  callbacks only as inline functions. The option takes effect where the JavaScript is generated, which is why the
  plugin must also be applied to the project that builds the executable.

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
    __krcUseMemo(); // "use memo"
    var attrs = Object();
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

### What Is Left As Is

- Hook calls that spread their dependencies (`useMemo(*deps) { }`), and any hook that makes such a call: React
  Compiler rejects a dependency list that is not an array literal.
- A component that uses its `ChildrenBuilder` in a way the plugin cannot rewrite (passing it to another function, for
  example). React Compiler does not memoize it, and it is reported as `REACT_UNSUPPORTED_SYNTAX`. With that rule off,
  run the compile task with `--info` to see these components.

### Development Warnings About `key`

In development, React may warn that `key` is not a prop, or that a props object with a `key` is spread into JSX. This
happens when an element's key changes while its props stay the same, because React Compiler caches the props object
separately from the `jsx()` call. Production builds are not affected.

## Linter

React Compiler memoizes only components and hooks that follow the Rules of React. It silently skips the rest or, if it
cannot see the violation, compiles them into code that behaves differently. The plugin checks the rules on the Kotlin
code, in every module. Each diagnostic is named after the
[`eslint-plugin-react-hooks`](https://react.dev/reference/eslint-plugin-react-hooks) rule it follows and has the level
that plugin's `recommended` preset gives that rule.

### Terms

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

The linter follows the code only as written: it does not look into called functions.

### ESLint Rules Without a Diagnostic

The remaining rules of the ESLint plugin have nothing to check in Kotlin. `config` and `gating` configure the Babel
plugin. `incompatible-library` corresponds to the `incompatibleHooks` option of vite-plugin-react-kotlinjs.
`component-hook-factories` is deprecated. `preserve-manual-memoization` is usually caused by incomplete dependencies,
which `REACT_EXHAUSTIVE_DEPS` reports.

## Wrappers

### Component Wrappers

The plugin rewrites a `@ComponentWrapper` function to take an already-built component, and each call site passes
either a lowered lambda or, when the lambda cannot be lowered, `FC(lambda)`. The declaring module checks the wrapper's
contract.

`@ComponentWrapper` is an error-level `@RequiresOptIn` marker, which the plugin opts into. The plugin also stamps the
wrapper in `.klib` metadata, and calling a wrapper that lacks the stamp fails the build. Wrappers compiled with plugin
1.0.2 or older have no stamp and must be recompiled.

### Element Wrappers

The declaring module verifies the requirements of an `@ElementWrapper` `invoke` and stamps it in `.klib` metadata.
Other modules lower only stamped wrappers to `jsx()`, so wrappers from modules compiled without the plugin are never
lowered. Call sites compiled without the plugin run the `invoke` body as is.
