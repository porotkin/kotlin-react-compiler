package com.github.porotkin.reactcompiler

import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact
import java.util.*

internal const val PLUGIN_ID = "io.github.porotkin.kotlin-react-compiler"

private val PROPERTIES: Properties =
    Properties().apply {
        ReactCompilerPlugin::class.java.getResourceAsStream("/kotlin-react-compiler.properties")!!.use(::load)
    }

internal val COMPILER_PLUGIN_ARTIFACT: SubpluginArtifact =
    SubpluginArtifact(
        groupId = PROPERTIES.getProperty("group"),
        artifactId = "compiler-plugin",
        version = PROPERTIES.getProperty("version"),
    )

/** The `@ComponentWrapper` / `@ElementWrapper` annotations, an `api` dependency of every main JS compilation. */
internal val ANNOTATIONS_DEPENDENCY: String =
    "${PROPERTIES.getProperty("group")}:annotations:${PROPERTIES.getProperty("version")}"

internal val KOTLIN_VERSION: String =
    PROPERTIES.getProperty("kotlin")
