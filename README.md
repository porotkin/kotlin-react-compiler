# Kotlin React Compiler

[![Test](https://github.com/porotkin/kotlin-react-compiler/actions/workflows/test.yml/badge.svg)](https://github.com/porotkin/kotlin-react-compiler/actions/workflows/test.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.porotkin.reactcompiler/compiler-plugin)](https://central.sonatype.com/artifact/io.github.porotkin.reactcompiler/compiler-plugin)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.porotkin.kotlin-react-compiler)](https://plugins.gradle.org/plugin/io.github.porotkin.kotlin-react-compiler)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.21-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![License](https://img.shields.io/github/license/porotkin/kotlin-react-compiler)](LICENSE)

A Kotlin/JS IR compiler plugin and Gradle plugin that lowers `kotlin-react` components (`FC`) and hooks (`useMemo`, `useCallback`, custom `use*` hooks) into React Compiler-compatible JavaScript shapes.

## Setup

Apply the plugin to a Kotlin/JS project:

```kotlin
plugins {
    kotlin("multiplatform") version "2.4.21"
    id("io.github.porotkin.kotlin-react-compiler") version "0.1.0"
}
```

## What It Lowers

- **Component lowering (`FC`):** Rewrites `FC { ... }` component lambdas from `($this$FC, props) -> Unit` into `(props) -> ReactNode?` functions that emit `"use memo"` at the start of the body, keep hook calls at the top level, lower `ChildrenBuilder` element DSL calls into direct `jsx(type, attrs, "@rdk/<line>_<col>")` calls, and annotate top-level properties initialized with a bare component with `@EagerInitialization`.
- **Hook argument reordering (`useMemo` / `useCallback`):** Rewrites `kotlin-react`'s `(vararg dependencies, callback)` hook calls to `useMemoRaw(callback, dependencies)` / `useCallbackRaw(callback, dependencies)` in IR before Kotlin/JS lowers arguments into reversed evaluation-order temporaries or applies Seskar `@JsRaw` value-class dependency array wrappers.
- **Custom hook directive injection (`use*`):** Prepends `js("\"use memo\"")` to top-level `use*` functions so React Compiler compiles them in `annotation` mode and preserves top-level hook calls inside hoisted `$lambda` helpers.
