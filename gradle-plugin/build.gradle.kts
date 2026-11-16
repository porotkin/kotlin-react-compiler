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

val testNodeSourceDir = layout.projectDirectory.dir("src/test/node")
val testNodeBuildDir = layout.buildDirectory.dir("test-node")

val installTestNodeModules = tasks.register<Exec>("installTestNodeModules") {
    val sourcePackageJson = testNodeSourceDir.file("package.json")
    val sourcePackageLock = testNodeSourceDir.file("package-lock.json")
    val targetDir = testNodeBuildDir
    val stampFile = testNodeBuildDir.map { it.file("node_modules/.package-lock.json") }

    inputs.file(sourcePackageJson).withPathSensitivity(PathSensitivity.NONE)
    inputs.file(sourcePackageLock).withPathSensitivity(PathSensitivity.NONE)
    outputs.file(stampFile)

    doFirst {
        val dir = targetDir.get().asFile.apply { mkdirs() }
        sourcePackageJson.asFile.copyTo(dir.resolve("package.json"), overwrite = true)
        sourcePackageLock.asFile.copyTo(dir.resolve("package-lock.json"), overwrite = true)
    }

    workingDir(targetDir)
    val npm = if (System.getProperty("os.name").lowercase().contains("win")) "npm.cmd" else "npm"
    commandLine(npm, "ci", "--no-audit", "--no-fund", "--ignore-scripts")
}

tasks.test {
    dependsOn(installTestNodeModules)
    dependsOn(rootProject.subprojects.map { "${it.path}:$PUBLISH_TO_TEST_REPOSITORY" })
    inputs.files(testRepository.map { fileTree(it) { include("**/$version/*") } })
        .withPropertyName("testRepository")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    systemProperty("pluginId", pluginId)
    systemProperty("testRepository", testRepository.get().asFile.absolutePath)
    systemProperty("pluginVersion", "$version")
    systemProperty("kotlinVersion", libs.versions.kotlin.get())
    systemProperty("kotlinReactVersion", libs.versions.kotlinWrappers.get())
    systemProperty("seskarVersion", libs.versions.seskar.get())
    systemProperty("testNodeDir", testNodeBuildDir.get().asFile.absolutePath)
}
