package com.github.porotkin.reactcompiler

/**
 * Marks a class whose instances React does not own, so effects and callbacks may write to them: a holder shared
 * through context, a store, an object that lives outside the component tree.
 *
 * React Compiler treats props, state, hook arguments and hook results as frozen, and does not compile a component or
 * hook whose callback writes to one. Where an effect or a callback writes to a value of a `@Mutable` class, the plugin
 * tells React Compiler the value is not React's ([mutableValue]); a write during render is still an error. DOM nodes
 * (`web.events.EventTarget`), refs (`react.RefObject`) and preact `Signal`s are treated so without the annotation.
 * Subclasses of a `@Mutable` class are `@Mutable` too.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Mutable
