package com.github.porotkin.reactcompiler

/**
 * React Compiler's `"use memo"` directive. The plugin starts each function it hands React Compiler with a call of it,
 * which vite-plugin-react-kotlinjs turns into the directive: the compiler parses a `js("\"use memo\"")` at every call.
 * Called as it is, it does nothing.
 */
@Suppress("FunctionName")
fun __krcUseMemo() {}
