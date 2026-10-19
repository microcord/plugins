// The Microcord plugin SDK and Gradle plugin come from Microcord's Maven, or from a Microcord checkout
// passed as `-PmicrocordDir=...`.
pluginManagement {
    providers.gradleProperty("microcordDir").orNull?.let { includeBuild(file(it).resolve("plugin-gradle")) }
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = uri("https://maven.git.hoodnet.work/microcord/microcord")
            credentials {
                username = providers.environmentVariable("MICROCORD_MAVEN_USER").orNull
                    ?: providers.gradleProperty("gpr.user").orNull
                password = providers.environmentVariable("MICROCORD_MAVEN_TOKEN").orNull
                    ?: providers.gradleProperty("gpr.key").orNull
            }
        }
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
        maven {
            url = uri("https://maven.git.hoodnet.work/microcord/microcord")
            credentials {
                username = providers.environmentVariable("MICROCORD_MAVEN_USER").orNull
                    ?: providers.gradleProperty("gpr.user").orNull
                password = providers.environmentVariable("MICROCORD_MAVEN_TOKEN").orNull
                    ?: providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}

rootProject.name = "microcord-plugins"
include(":reviewdb", ":messagelogger")
