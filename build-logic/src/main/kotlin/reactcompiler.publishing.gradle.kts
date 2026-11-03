import reactcompiler.buildlogic.TEST_REPOSITORY
import reactcompiler.buildlogic.testRepository

plugins {
    `maven-publish`
}

publishing.repositories.maven(testRepository) {
    name = TEST_REPOSITORY
}
