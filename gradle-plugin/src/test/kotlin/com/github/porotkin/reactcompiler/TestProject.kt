package com.github.porotkin.reactcompiler

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File

internal class TestProject(
    private val dir: File,
    private val kotlinVersion: String = System.getProperty("kotlinVersion"),
) {
    private val repository = System.getProperty("testRepository")

    private val pluginId = System.getProperty("pluginId")

    private val pluginVersion = System.getProperty("pluginVersion")

    private val modules = mutableListOf(STUBS_MODULE)

    /**
     * Adds a module compiled with the plugin. It depends on the React stubs, which, like kotlin-react, are compiled
     * without the plugin, and on the modules in [dependsOn].
     */
    fun module(
        name: String,
        source: String,
        vararg dependsOn: String,
        applyPlugin: Boolean = true,
    ) {
        modules += name
        val dependencies = (listOf(STUBS_MODULE) + dependsOn).joinToString("; ") { "implementation(project(\":$it\"))" }
        val pluginsBlock = if (applyPlugin) "plugins {\n    id(\"$pluginId\")\n}\n" else ""
        write(
            "$name/build.gradle.kts",
            """
            ${pluginsBlock}kotlin { sourceSets { jsMain { dependencies { $dependencies } } } }
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

    private fun writeRootBuild() {
        write("$STUBS_MODULE/build.gradle.kts", "")
        write("$STUBS_MODULE/src/jsMain/kotlin/ReactStubs.kt", REACT_STUBS)
        write("$STUBS_MODULE/src/jsMain/kotlin/ReactRawStubs.kt", REACT_RAW_STUBS)
        write("$STUBS_MODULE/src/jsMain/kotlin/ReactJsxStubs.kt", REACT_JSX_STUBS)
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

private val REACT_STUBS =
    """
    package react

    annotation class ElementBuilder

    external interface ReactNode

    interface Props

    interface PropsWithChildren : Props {
        var children: Any?
    }

    interface FragmentProps : PropsWithChildren, ChildrenBuilder

    external interface ElementType<P : Props>

    external interface FC<P : Props> : ElementType<P>

    @JsModule("react")
    external object ReactModule {
        val Fragment: ElementType<FragmentProps>
        fun <T> useState(initial: T): Array<Any?>
    }

    val Fragment: ElementType<FragmentProps>
        get() = ReactModule.Fragment

    interface ChildrenBuilder {
        @ElementBuilder
        operator fun <P : Props> ElementType<P>.invoke(block: P.() -> Unit) {}

        operator fun String.unaryPlus() {}
    }

    fun ChildrenBuilder.setDefaultKey(key: String) {}

    fun <P : Props> FC(block: ChildrenBuilder.(props: P) -> Unit): FC<P> =
        block.unsafeCast<FC<P>>()

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
        type.unsafeCast<ReactNode>()
    """.trimIndent() + "\n"
