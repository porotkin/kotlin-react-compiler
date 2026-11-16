@file:JsModule("react")

package com.github.porotkin.reactcompiler

/**
 * React's effect hooks, which kotlin-react keeps internal (`useEffectRaw`, `useLayoutEffectRaw`).
 * Lowered effect calls invoke them directly with an inline callback returning `undefined` or a cleanup function,
 * so React Compiler sees standard React effect calls and Kotlin/JS never returns `Unit` as an effect cleanup.
 */
@JsName("useEffect")
external fun useEffect(
    effect: () -> Any?,
    dependencies: Array<out Any?> = definedExternally,
)

@JsName("useLayoutEffect")
external fun useLayoutEffect(
    effect: () -> Any?,
    dependencies: Array<out Any?> = definedExternally,
)

@JsName("useInsertionEffect")
external fun useInsertionEffect(
    effect: () -> Any?,
    dependencies: Array<out Any?> = definedExternally,
)
