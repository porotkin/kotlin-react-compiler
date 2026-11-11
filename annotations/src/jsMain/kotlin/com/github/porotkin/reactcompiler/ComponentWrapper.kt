package com.github.porotkin.reactcompiler

/**
 * Marks a final, non-overriding function returning `FC` that takes one `ChildrenBuilder.(props) -> Unit` render lambda
 * and uses it only as `FC(render)` (or forwards it to another [ComponentWrapper]).
 *
 * The plugin rewrites the body to take an already-built component, so every call site must be compiled with the
 * plugin. Calling a wrapper therefore requires opt-in to this annotation: the Gradle plugin grants it to the
 * compilations it applies to, and a module compiled without the plugin fails to compile. The declaring module must be
 * compiled with the plugin as well: calling a wrapper the plugin has not rewritten fails the build.
 */
@RequiresOptIn(
    message = "Call sites of a @ComponentWrapper function must be compiled with the Kotlin React Compiler plugin.",
    level = RequiresOptIn.Level.ERROR,
)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ComponentWrapper
