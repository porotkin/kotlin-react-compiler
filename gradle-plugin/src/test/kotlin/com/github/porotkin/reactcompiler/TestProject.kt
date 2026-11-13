package com.github.porotkin.reactcompiler

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File

internal class TestProject(
    private val dir: File,
    private val kotlinVersion: String = System.getProperty("kotlinVersion"),
    private val externalProps: Boolean = false,
) {
    private val repository = System.getProperty("testRepository")

    private val pluginId = System.getProperty("pluginId")

    private val pluginVersion = System.getProperty("pluginVersion")

    private val modules = mutableListOf(STUBS_MODULE)

    /**
     * Adds a module compiled with the plugin. It depends on the React stubs, which, like kotlin-react, are compiled
     * without the plugin, and on the modules in [dependsOn]. A module compiled without the plugin gets the annotations
     * artifact explicitly, as a project that adds it by hand would.
     */
    fun module(
        name: String,
        source: String,
        vararg dependsOn: String,
        applyPlugin: Boolean = true,
        configuration: String = "",
    ) {
        modules += name
        val annotations = if (applyPlugin) emptyList() else listOf("implementation(\"$ANNOTATIONS_MODULE:$pluginVersion\")")
        val dependencies = ((listOf(STUBS_MODULE) + dependsOn).map { "implementation(project(\":$it\"))" } + annotations).joinToString("; ")
        val pluginsBlock = if (applyPlugin) "plugins {\n    id(\"$pluginId\")\n}\n" else ""
        write(
            "$name/build.gradle.kts",
            """
            ${pluginsBlock}kotlin { sourceSets { jsMain { dependencies { $dependencies } } } }
            $configuration
            """,
        )
        write("$name/src/jsMain/kotlin/${name.replaceFirstChar(Char::uppercaseChar)}.kt", source)
    }

    fun run(vararg tasks: String): BuildResult {
        writeRootBuild()

        return GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(*tasks, "--stacktrace")
            .forwardOutput()
            .build()
    }

    /** Runs [tasks] whether or not the build then fails. */
    fun runUnchecked(vararg tasks: String): BuildResult {
        writeRootBuild()

        return GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(*tasks, "--stacktrace")
            .run()
    }

    fun runAndFail(vararg tasks: String): BuildResult {
        writeRootBuild()

        return GradleRunner.create()
            .withProjectDir(dir)
            .withArguments(*tasks, "--stacktrace")
            .buildAndFail()
    }

    private fun writeRootBuild() {
        write("$STUBS_MODULE/build.gradle.kts", "")
        write("$STUBS_MODULE/src/jsMain/kotlin/ReactStubs.kt", if (externalProps) REACT_EXTERNAL_STUBS else REACT_STUBS)
        write("$STUBS_MODULE/src/jsMain/kotlin/ReactRawStubs.kt", REACT_RAW_STUBS)
        write("$STUBS_MODULE/src/jsMain/kotlin/ReactJsxStubs.kt", REACT_JSX_STUBS)
        write("$STUBS_MODULE/src/jsMain/kotlin/ReactHookStubs.kt", REACT_HOOK_STUBS)
        write(
            "settings.gradle.kts",
            """
            pluginManagement { repositories { maven("$repository"); gradlePluginPortal(); mavenCentral() } }
            dependencyResolutionManagement {
                repositories { maven("$repository"); mavenCentral() }
            }
            rootProject.name = "test-project"
            include(${modules.joinToString { "\"$it\"" }})
            """,
        )
        write(
            "build.gradle.kts",
            """
            plugins {
                kotlin("multiplatform") version "$kotlinVersion" apply false
                id("$pluginId") version "$pluginVersion" apply false
            }
            subprojects {
                apply(plugin = "org.jetbrains.kotlin.multiplatform")
                extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
                    compilerOptions {
                        freeCompilerArgs.addAll("-Xverify-ir=error")
                    }
                    js {
                        browser()
                        binaries.executable()
                        useEsModules()
                    }
                }
            }
            """,
        )
    }

    private fun write(
        path: String,
        text: String,
    ) {
        File(dir, path).apply { parentFile.mkdirs() }.writeText(text.trimIndent() + "\n")
    }
}

private const val STUBS_MODULE = "react-stubs"

private const val ANNOTATIONS_MODULE = "io.github.porotkin.reactcompiler:annotations"

private val REACT_STUBS =
    """
    package react

    annotation class ElementBuilder

    external interface ReactNode

    interface Props {
        var key: Any?
            get() = null
            set(_) {}

        operator fun Props.unaryPlus() {}
    }

    fun Props.css(block: Props.() -> Unit) {
        block()
    }

    interface PropsWithChildren : Props, ChildrenBuilder {
        var children: Any?
    }

    interface PropsWithValue<T> : PropsWithChildren {
        var value: T
    }

    interface FragmentProps : PropsWithChildren

    external interface ElementType<P : Props>

    external interface FC<P : Props> : ElementType<P>

    external interface Provider<T> : ElementType<PropsWithValue<T>>

    interface ValueContextProps<T> : PropsWithChildren {
        var value: T
        var setter: (T) -> Unit
    }

    @JsModule("react")
    external object ReactModule {
        val Fragment: ElementType<FragmentProps>
        fun <T> useState(initial: T): Array<Any?>
    }

    val Fragment: ElementType<FragmentProps>
        get() = ReactModule.Fragment

    interface ChildrenBuilder {
        @ElementBuilder
        operator fun <P : Props> ElementType<P>.invoke() {}

        @ElementBuilder
        operator fun <P : Props> ElementType<P>.invoke(block: P.() -> Unit) {}

        @ElementBuilder
        operator fun <T> Provider<T>.invoke(
            value: T,
            block: ChildrenBuilder.() -> Unit,
        ) {}

        operator fun String.unaryPlus() {}

        operator fun Char.unaryPlus() {}

        fun <P : Props> child(
            type: ElementType<P>,
            props: P,
        ) {}
    }

    fun ChildrenBuilder.setDefaultKey(key: String) {}

    @Suppress("NOTHING_TO_INLINE")
    inline fun <P : Props> FC(
        crossinline block: ChildrenBuilder.(props: P) -> Unit,
    ): FC<P> =
        ({ props: P ->
            Fragment.unsafeCast<ChildrenBuilder>().block(props)
        }).unsafeCast<FC<P>>()

    fun <P : Props> memo(type: FC<P>): FC<P> =
        type

    fun <T> useMemo(vararg dependencies: Any?, callback: () -> T): T =
        callback()

    fun <T : Function<*>> useCallback(vararg dependencies: Any?, callback: T): T =
        callback
    """.trimIndent() + "\n"

private val REACT_EXTERNAL_STUBS =
    """
    @file:Suppress(
        "WRONG_BODY_OF_EXTERNAL_DECLARATION",
        "WRONG_EXTERNAL_DECLARATION",
        "EXTENSION_FUNCTION_IN_EXTERNAL_DECLARATION",
        "INLINE_EXTERNAL_DECLARATION",
        "NON_ABSTRACT_MEMBER_OF_EXTERNAL_INTERFACE",
        "DECLARATION_CANT_BE_INLINED",
        "NOTHING_TO_INLINE",
    )

    package react

    annotation class ElementBuilder

    external interface ReactNode

    external interface Props {
        var key: Any?
    }

    external interface PropsWithChildren : Props, ChildrenBuilder {
        var children: Any?
    }

    external interface PropsWithValue<T> : PropsWithChildren {
        var value: T
    }

    external interface FragmentProps : PropsWithChildren

    external interface ElementType<P : Props>

    external interface FC<P : Props> : ElementType<P>

    external interface Provider<T> : ElementType<PropsWithValue<T>>

    external interface ValueContextProps<T> : PropsWithChildren {
        var value: T
        var setter: (T) -> Unit
    }

    @JsModule("react")
    external object ReactModule {
        val Fragment: ElementType<FragmentProps>
        fun <T> useState(initial: T): Array<Any?>
    }

    val Fragment: ElementType<FragmentProps>
        get() = ReactModule.Fragment

    sealed external interface ChildrenBuilder {
        @ElementBuilder
        inline operator fun <P : Props> ElementType<P>.invoke() {}

        @ElementBuilder
        inline operator fun <P : Props> ElementType<P>.invoke(
            noinline block: P.() -> Unit,
        ) {
            addChild(this, block)
        }

        @ElementBuilder
        inline operator fun <T> Provider<T>.invoke(
            value: T,
            noinline block: ChildrenBuilder.() -> Unit,
        ) {}

        inline operator fun String.unaryPlus() {
            this@ChildrenBuilder.asDynamic().children = this
        }

        inline operator fun Char.unaryPlus() {}

        inline fun <P : Props> child(
            type: ElementType<P>,
            props: P,
        ) {}
    }

    fun <P : Props> ChildrenBuilder.addChild(
        type: ElementType<P>,
        block: P.() -> Unit,
    ) {
        val p = js("({})").unsafeCast<P>()
        p.block()
        asDynamic().recorded.push(react.jsx.runtime.jsx(type, p, "fallback-key"))
    }

    fun ChildrenBuilder.setDefaultKey(key: String) {}

    inline fun <P : Props> FC(
        crossinline block: ChildrenBuilder.(props: P) -> Unit,
    ): FC<P> =
        ({ props: P ->
            Fragment.unsafeCast<ChildrenBuilder>().block(props)
        }).unsafeCast<FC<P>>()

    fun <P : Props> memo(type: FC<P>): FC<P> =
        type

    fun <T> useMemo(vararg dependencies: Any?, callback: () -> T): T =
        callback()

    fun <T : Function<*>> useCallback(vararg dependencies: Any?, callback: T): T =
        callback
    """.trimIndent() + "\n"

private val REACT_RAW_STUBS =
    """
    package react.raw

    @JsModule("react")
    external object ReactRaw {
        fun <T> useMemo(callback: () -> T, dependencies: Array<out Any?>): T
        fun <T : Function<*>> useCallback(callback: T, dependencies: Array<out Any?>): T
    }

    fun <T> useMemoRaw(callback: () -> T, dependencies: Array<out Any?>): T =
        ReactRaw.useMemo(callback, dependencies)

    fun <T : Function<*>> useCallbackRaw(callback: T, dependencies: Array<out Any?>): T =
        ReactRaw.useCallback(callback, dependencies)
    """.trimIndent() + "\n"

private val REACT_JSX_STUBS =
    """
    package react.jsx.runtime

    import react.ElementType
    import react.Props
    import react.ReactNode

    fun <P : Props> jsx(type: ElementType<P>, props: P?, defaultKey: Any?): ReactNode =
        js("({ type: type, props: props, key: (props != null && props.key != null) ? props.key : defaultKey })").unsafeCast<ReactNode>()
    """.trimIndent() + "\n"

/** The refs, state and effects of kotlin-react, which the lint rules tell by their types. */
private val REACT_HOOK_STUBS =
    """
    package react

    import kotlin.reflect.KProperty

    external interface RefObject<T : Any> {
        var current: T?
    }

    fun <T : Any> useRef(initialValue: T? = null): RefObject<T> =
        js("({ current: initialValue })").unsafeCast<RefObject<T>>()

    class RefValueInstance<T>(private var value: T) {
        operator fun getValue(thisRef: Nothing?, property: KProperty<*>): T = value

        operator fun setValue(thisRef: Nothing?, property: KProperty<*>, value: T) {
            this.value = value
        }
    }

    fun <T : Any> useRefValue(initialValue: T): RefValueInstance<T> =
        RefValueInstance(initialValue)

    class StateSetter<T> {
        operator fun invoke(value: T) {}

        operator fun invoke(transform: (T) -> T) {}
    }

    class StateInstance<T>(private val value: T) {
        operator fun component1(): T = value

        operator fun component2(): StateSetter<T> = StateSetter()

        operator fun getValue(thisRef: Nothing?, property: KProperty<*>): T = value

        operator fun setValue(thisRef: Nothing?, property: KProperty<*>, value: T) {}
    }

    fun <T> useState(initialValue: T): StateInstance<T> =
        StateInstance(initialValue)

    fun <T> useState(initializer: () -> T): StateInstance<T> =
        StateInstance(initializer())

    fun useEffect(effect: suspend () -> Unit) {}

    fun useEffect(vararg dependencies: Any?, effect: suspend () -> Unit) {}

    fun useEffectOnce(effect: suspend () -> Unit) {}

    fun useLayoutEffect(vararg dependencies: Any?, effect: suspend () -> Unit) {}

    external interface Context<T>

    fun <T> use(context: Context<T>): T =
        js("null").unsafeCast<T>()
    """.trimIndent() + "\n"
