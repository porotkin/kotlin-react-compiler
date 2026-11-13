package com.github.porotkin.reactcompiler.lint

/** How a broken [LintRule] is reported. */
internal enum class LintLevel {
    ERROR,
    WARNING,
    OFF,
    ;

    companion object {
        fun parse(value: String): LintLevel? =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * A rule the linter checks. Its name is the name of the diagnostic: `@Suppress("<name>")` silences it on a
 * declaration, and the `lint` plugin option sets its [LintLevel].
 *
 * The rules are the [Rules of React](https://react.dev/reference/rules) as React's own linter enforces them: each
 * one but the last carries the name of a rule of
 * [`eslint-plugin-react-hooks`](https://react.dev/reference/eslint-plugin-react-hooks), or of the Rule of React it
 * checks where that plugin has no rule for it, and the level the plugin's `recommended` preset gives it.
 */
internal enum class LintRule(val default: LintLevel) {
    /** `rules-of-hooks`: hooks are called at the top level of a component or of a hook, and nowhere else. */
    REACT_RULES_OF_HOOKS(LintLevel.ERROR),

    /** `exhaustive-deps`: the dependencies of a hook name every value its callback reads. */
    REACT_EXHAUSTIVE_DEPS(LintLevel.WARNING),

    /** "Never pass around Hooks as regular values": a hook is a top-level function that is only ever called. */
    REACT_HOOKS_AS_VALUES(LintLevel.ERROR),

    /** `refs`: a ref is not read or written while rendering. */
    REACT_REFS(LintLevel.ERROR),

    /** `immutability`: props, state, hook arguments, hook results and values passed to elements are not modified. */
    REACT_IMMUTABILITY(LintLevel.ERROR),

    /** `globals`: rendering does not assign or modify what is declared outside the component or hook. */
    REACT_GLOBALS(LintLevel.ERROR),

    /** `purity`: rendering does not call functions that return something else every time. */
    REACT_PURITY(LintLevel.ERROR),

    /** `set-state-in-render`: state is not set unconditionally while rendering. */
    REACT_SET_STATE_IN_RENDER(LintLevel.ERROR),

    /** `set-state-in-effect`: an effect does not set state as soon as it runs. */
    REACT_SET_STATE_IN_EFFECT(LintLevel.ERROR),

    /** `static-components`: a component is not created while another one renders. */
    REACT_STATIC_COMPONENTS(LintLevel.ERROR),

    /** `error-boundaries`: `try`/`catch` is not used to catch what a child component throws. */
    REACT_ERROR_BOUNDARIES(LintLevel.ERROR),

    /** `use-memo`: `useMemo` computes a value, and the value is used. */
    REACT_USE_MEMO(LintLevel.ERROR),

    /**
     * Not a Rule of React: a file that exports a component exports nothing else, which Fast Refresh needs. It is
     * `only-export-components` of `eslint-plugin-react-refresh`.
     */
    REACT_ONLY_EXPORT_COMPONENTS(LintLevel.WARNING),
    ;

    companion object {
        fun parse(name: String): LintRule? =
            entries.firstOrNull { it.name == name }
    }
}

/** The level of every rule: its default, unless the `lint` plugin option says otherwise. */
internal class LintConfig(
    private val levels: Map<LintRule, LintLevel>,
) {
    fun level(rule: LintRule): LintLevel =
        levels[rule] ?: rule.default

    companion object {
        /** `off` turns every rule off; `all:<level>` sets every rule, `<RULE>:<level>` one rule. Later options win. */
        const val OFF = "off"
        const val ALL = "all"

        /** Reads the values of the `lint` option; a value it cannot read is passed to [onInvalid]. */
        fun parse(
            options: List<String>,
            onInvalid: (String) -> Unit,
        ): LintConfig {
            val levels = mutableMapOf<LintRule, LintLevel>()
            for (option in options) {
                if (option == OFF) {
                    LintRule.entries.associateWithTo(levels) { LintLevel.OFF }
                    continue
                }
                val name = option.substringBefore(':')
                val level = LintLevel.parse(option.substringAfter(':', ""))
                val rules = if (name == ALL) LintRule.entries else listOfNotNull(LintRule.parse(name))
                if (level == null || rules.isEmpty()) onInvalid(option) else rules.associateWithTo(levels) { level }
            }
            return LintConfig(levels)
        }
    }
}
