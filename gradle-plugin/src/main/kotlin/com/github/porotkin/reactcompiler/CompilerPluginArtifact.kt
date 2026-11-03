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

internal val KOTLIN_VERSION: String =
    PROPERTIES.getProperty("kotlin")
