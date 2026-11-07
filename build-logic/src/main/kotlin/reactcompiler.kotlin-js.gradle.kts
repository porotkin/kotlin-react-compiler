plugins {
    kotlin("multiplatform")
}

kotlin {
    js()

    sourceSets.commonMain.dependencies {
        implementation(kotlin("stdlib"))
    }
}
