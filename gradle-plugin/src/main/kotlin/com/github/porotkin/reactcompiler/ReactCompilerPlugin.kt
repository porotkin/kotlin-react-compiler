package com.github.porotkin.reactcompiler

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.withType
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
        }

        return kotlinCompilation.target.project.provider { emptyList() }
    }
}
