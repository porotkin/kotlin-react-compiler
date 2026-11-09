package com.github.porotkin.reactcompiler

/**
 * Marks a final, non-overriding member `operator fun invoke` on a class with a single public `ElementType` property
 * that takes `ChildrenBuilder`, prop parameters matching writable properties on the element's `PropsWithChildren`,
 * and a `ChildrenBuilder.() -> Unit` block, and whose body only invokes that `ElementType` property, assigning each
 * parameter to the matching prop and calling `block()`.
 *
 * Call sites compiled with the plugin are lowered to a single `jsx()` (or, with several children, `jsxs()`) call; other
 * call sites run the body as is.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class ElementWrapper
