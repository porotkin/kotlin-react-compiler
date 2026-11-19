package com.github.porotkin.reactcompiler

/**
 * A new empty object, printed as `Object()`: the plugin starts each props object with it, as the compiler parses a
 * `js("({})")` at every call. vite-plugin-react-kotlinjs turns the calls in compiled functions back into `{}`.
 */
@JsName("Object")
external fun emptyObject(): dynamic
