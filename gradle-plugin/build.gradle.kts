plugins {
    `kotlin-dsl`
    id("reactcompiler.kotlin-jvm")
    alias(libs.plugins.gradlePluginPublish)
}

dependencies {
    compileOnly(libs.kotlin.gradlePluginApi)
}

val repositoryUrl = providers.gradleProperty("POM_URL")

val pluginId = "io.github.porotkin.kotlin-react-compiler"

gradlePlugin {
    website = repositoryUrl
    vcsUrl = repositoryUrl

    plugins {
        register("kotlinReactCompiler") {
            id = pluginId
            displayName = "Kotlin React Compiler"
            description = "Lowers kotlin-react components and hooks in Kotlin/JS IR for the React Compiler"
            implementationClass = "com.github.porotkin.reactcompiler.ReactCompilerPlugin"
            tags = listOf(
                "kotlin",
                "kotlin-js",
                "react",
                "react-compiler",
            )
        }
    }
}

tasks.processResources {
    val properties = mapOf(
        "group" to "${project.group}",
        "version" to "${project.version}",
        "kotlin" to libs.versions.kotlin.get(),
    )

    inputs.properties(properties)

    filesMatching("kotlin-react-compiler.properties") {
        expand(properties)
    }
}
