// The Microcord plugin SDK and Gradle plugin come from Microcord's public Maven branch, or from a Microcord checkout
// passed as `-PmicrocordDir=...`.
pluginManagement {
    providers.gradleProperty("microcordDir").orNull?.let { includeBuild(file(it).resolve("plugin-gradle")) }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://raw.git.hoodnet.work/microcord/microcord/maven")
    }
    plugins {
        id("dev.mantikafasi.manticord.plugin") version "1.0.0"
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://raw.git.hoodnet.work/microcord/microcord/maven")
    }
}

rootProject.name = "microcord-plugins"
include(":reviewdb", ":messagelogger", ":translate")
