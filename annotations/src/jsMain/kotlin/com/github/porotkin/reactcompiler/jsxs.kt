@file:JsModule("react/jsx-runtime")

package com.github.porotkin.reactcompiler

/**
 * React's `jsxs`, which kotlin-react keeps internal. Lowered elements whose `children` the plugin sets to several nodes
 * call it, as kotlin-react's `jsx` does for several builder children, so React does not check them for keys as a list.
 */
@JsName("jsxs")
external fun jsxs(
    type: Any,
    props: Any,
    key: Any? = definedExternally,
): Any
