import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

val libs = the<LibrariesForLibs>()

val javaRelease = 17

kotlin {
    jvmToolchain(21)
    compilerOptions.jvmTarget = JvmTarget.fromTarget("$javaRelease")
}

tasks.withType<JavaCompile>().configureEach {
    options.release = javaRelease
}

dependencies {
    compileOnly(kotlin("stdlib"))
    testImplementation(kotlin("stdlib"))
}

testing.suites.named<JvmTestSuite>("test") {
    useJUnitJupiter(libs.versions.junit)
}
