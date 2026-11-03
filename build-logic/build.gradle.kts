import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(files(LibrariesForLibs::class.java.protectionDomain.codeSource.location))
}
