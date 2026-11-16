package com.github.porotkin.reactcompiler

/**
 * Recorded in `.klib` metadata by the compiler plugin on verified `@ComponentWrapper` and `@ElementWrapper` functions
 * so downstream and incremental compilations know the declaring module was compiled with the plugin.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
internal annotation class Stamped
