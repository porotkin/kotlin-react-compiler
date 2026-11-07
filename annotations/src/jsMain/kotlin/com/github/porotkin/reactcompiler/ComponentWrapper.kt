package com.github.porotkin.reactcompiler

/**
 * Marks a function returning `FC` that takes one `ChildrenBuilder.(props) -> Unit` render lambda and uses it only as
 * `FC(render)` (or forwards it to another [ComponentWrapper]).
 *
 * The plugin rewrites the body to take an already-built component, so every call site must be compiled with the
 * plugin.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ComponentWrapper
