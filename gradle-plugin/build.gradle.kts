import reactcompiler.buildlogic.PUBLISH_TO_TEST_REPOSITORY
import reactcompiler.buildlogic.testRepository

plugins {
    `kotlin-dsl`
    id("reactcompiler.kotlin-jvm")
    id("reactcompiler.publishing")
    alias(libs.plugins.gradlePluginPublish)
}

dependencies {
    compileOnly(libs.kotlin.gradlePluginApi)

    testImplementation(gradleTestKit())
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

tasks.test {
    dependsOn(rootProject.subprojects.map { "${it.path}:$PUBLISH_TO_TEST_REPOSITORY" })
    inputs.files(testRepository.map { fileTree(it) { include("**/$version/*") } })
        .withPropertyName("testRepository")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    systemProperty("pluginId", pluginId)
    systemProperty("testRepository", testRepository.get().asFile.absolutePath)
    systemProperty("pluginVersion", "$version")
    systemProperty("kotlinVersion", libs.versions.kotlin.get())
}
