plugins {
    alias(libs.plugins.android.application)
    id("dev.mantikafasi.manticord.plugin")
}

manticordPlugin {
    id.set("dev.mantikafasi.manticord.messagelogger")
    entrypoint.set("dev.mantikafasi.manticord.messagelogger.MessageLoggerPlugin")
    description.set("Keeps deleted messages in chat and shows message edit history")
    // Message delete/update interceptors and decorators.
    manticordVersionMinimum.set(2)
    manticordVersionMaximumExclusive.set(3)
    async()
}

android {
    namespace = "dev.mantikafasi.manticord.messagelogger"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "dev.mantikafasi.manticord.messagelogger"
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
