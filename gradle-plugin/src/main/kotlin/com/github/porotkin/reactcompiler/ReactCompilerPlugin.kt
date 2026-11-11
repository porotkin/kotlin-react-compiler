package com.github.porotkin.reactcompiler

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.HasConfigurableKotlinCompilerOptions
import org.jetbrains.kotlin.gradle.plugin.*

class ReactCompilerPlugin : KotlinCompilerPluginSupportPlugin {
    override fun apply(target: Project): Unit = with(target) {
        plugins.withType<KotlinBasePlugin>().configureEach {
            if (pluginVersion != KOTLIN_VERSION) {
                logger.warn("Kotlin React Compiler is built for Kotlin $KOTLIN_VERSION, not $pluginVersion, and may fail.")
            }
        }
    }

    override fun isApplicable(kotlinCompilation: KotlinCompilation<*>): Boolean =
        kotlinCompilation.platformType == KotlinPlatformType.js

    override fun getCompilerPluginId(): String =
        PLUGIN_ID

    override fun getPluginArtifact(): SubpluginArtifact =
        COMPILER_PLUGIN_ARTIFACT

    override fun applyToCompilation(kotlinCompilation: KotlinCompilation<*>): Provider<List<SubpluginOption>> {
        // Test compilations see the annotations through their associated main compilation
        if (kotlinCompilation.name == KotlinCompilation.MAIN_COMPILATION_NAME) {
            kotlinCompilation.defaultSourceSet.dependencies { api(ANNOTATIONS_DEPENDENCY) }
            // React Compiler takes a hook callback only as an inline function, not as a call to a function returning one.
            // The option counts where the JavaScript is generated, which is why it is set for the whole target.
            (kotlinCompilation.target as? HasConfigurableKotlinCompilerOptions<*>)
                ?.compilerOptions?.freeCompilerArgs?.add(INLINE_LAMBDAS)
        }
        // Only modules compiled with the plugin may call @ComponentWrapper functions, whose bodies it rewrites
        kotlinCompilation.compileTaskProvider.configure { compilerOptions.optIn.add(COMPONENT_WRAPPER) }

        return kotlinCompilation.target.project.provider { emptyList() }
    }
}
