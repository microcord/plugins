plugins {
    alias(libs.plugins.android.application)
    id("dev.mantikafasi.manticord.plugin")
}

manticordPlugin {
    id.set("dev.mantikafasi.manticord.lastfm")
    entrypoint.set("dev.mantikafasi.manticord.lastfm.LastFmPlugin")
    description.set("Shows your Last.fm now playing as Discord rich presence")
    // Activities: scope.activities.
    manticordVersionMinimum.set(3)
    manticordVersionMaximumExclusive.set(4)
    async()
}

android {
    namespace = "dev.mantikafasi.manticord.lastfm"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "dev.mantikafasi.manticord.lastfm"
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
