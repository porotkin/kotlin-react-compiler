plugins {
    id("reactcompiler.kotlin-jvm")
    id("reactcompiler.publishing")
    alias(libs.plugins.mavenPublish)
}

dependencies {
    compileOnly(libs.kotlin.compilerEmbeddable)

    testImplementation(libs.kotlin.compilerEmbeddable)
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
            "org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI",
        )
    }
}
