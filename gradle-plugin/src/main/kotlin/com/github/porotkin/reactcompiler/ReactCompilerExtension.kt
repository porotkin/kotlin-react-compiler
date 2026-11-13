package com.github.porotkin.reactcompiler

import org.gradle.api.Action
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Nested

/** `reactCompiler { }`: the settings of the Kotlin React Compiler plugin. */
abstract class ReactCompilerExtension {
    /** The Rules of React linter. */
    @get:Nested
    abstract val lint: ReactLintExtension

    fun lint(configure: Action<in ReactLintExtension>) {
        configure.execute(lint)
    }
}

/**
 * The linter checks components and hooks against the Rules of React while the module compiles. Each rule is reported
 * as a compiler diagnostic under its name (`REACT_RULES_OF_HOOKS`, ...), at the level it has here.
 */
abstract class ReactLintExtension {
    /** Whether the linter runs. `true` by default. */
    abstract val enabled: Property<Boolean>

    /** The levels set so far, as `<RULE>:<level>`: a later one wins. */
    abstract val levels: ListProperty<String>

    /** Makes each of [rules] fail the build. */
    fun error(vararg rules: String) {
        set(ReactLintLevel.ERROR, rules)
    }

    /** Makes each of [rules] a warning. */
    fun warning(vararg rules: String) {
        set(ReactLintLevel.WARNING, rules)
    }

    /** Turns each of [rules] off. */
    fun off(vararg rules: String) {
        set(ReactLintLevel.OFF, rules)
    }

    /** Sets every rule to [level]: what [error], [warning] and [off] say after it still applies. */
    fun all(level: ReactLintLevel) {
        set(level, arrayOf(ALL_RULES))
    }

    private fun set(
        level: ReactLintLevel,
        rules: Array<out String>,
    ) {
        rules.forEach { levels.add("$it:${level.name.lowercase()}") }
    }
}

/** How a broken lint rule is reported. */
enum class ReactLintLevel {
    /** Fails the build. */
    ERROR,

    /** Prints a warning. */
    WARNING,

    /** Is not checked. */
    OFF,
}
