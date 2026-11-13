package com.github.porotkin.reactcompiler.lint

/** How a broken [LintRule] is reported. */
internal enum class LintLevel {
    ERROR,
    WARNING,
    OFF,
}

/**
 * A rule the linter checks. Its name is the name of the diagnostic: `@Suppress("<name>")` silences it on a
 * declaration.
 *
 * The rules are the [Rules of React](https://react.dev/reference/rules) as React's own linter enforces them: each
 * one carries the name of a rule of
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
}

/** The level of every rule: its default, unless another one is set for it. */
internal class LintConfig(
    private val levels: Map<LintRule, LintLevel>,
) {
    fun level(rule: LintRule): LintLevel =
        levels[rule] ?: rule.default
}
