package com.github.porotkin.reactcompiler

/**
 * Returns [value]. The plugin calls it, in its own file so its module exports nothing else: where an effect or a
 * callback writes to a [Mutable] value, the value is read through this function, which vite-plugin-react-kotlinjs types
 * for React Compiler as a value from outside React (`returnValueKind: 'global'`). React Compiler then accepts the write
 * in the callback, keeps the value a dependency of what reads it, and still rejects the write during render.
 */
fun <T> mutableValue(value: T): T = value
