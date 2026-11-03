package reactcompiler.buildlogic

import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider

const val TEST_REPOSITORY = "test"

val PUBLISH_TO_TEST_REPOSITORY = "publishAllPublicationsTo${TEST_REPOSITORY.replaceFirstChar(Char::uppercaseChar)}Repository"

val Project.testRepository: Provider<Directory>
    get() = rootProject.layout.buildDirectory.dir("test-repository")
