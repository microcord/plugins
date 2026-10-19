plugins {
    alias(libs.plugins.android.application)
    id("dev.mantikafasi.manticord.plugin")
}

manticordPlugin {
    id.set("dev.mantikafasi.manticord.translate")
    entrypoint.set("dev.mantikafasi.manticord.translate.TranslatePlugin")
    description.set("Translates messages and sends translated text")
    // Message decorators and async slash commands.
    manticordVersionMinimum.set(2)
    manticordVersionMaximumExclusive.set(3)
    async()
}

android {
    namespace = "dev.mantikafasi.manticord.translate"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "dev.mantikafasi.manticord.translate"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    compileOnly(rootProject.extra["microcordSdk"]!!)
    compileOnly(libs.rxjava)
}
