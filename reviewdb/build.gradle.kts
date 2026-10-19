plugins {
    alias(libs.plugins.android.application)
    id("dev.mantikafasi.manticord.plugin")
}

manticordPlugin {
    id.set("dev.mantikafasi.manticord.reviewdb")
    entrypoint.set("dev.mantikafasi.manticord.reviewdb.ReviewDbPlugin")
    description.set("ReviewDB user and server reviews")
    manticordVersionMinimum.set(1)
    manticordVersionMaximumExclusive.set(2)
    usesResources.set(true)
    async()
}

android {
    namespace = "dev.mantikafasi.manticord.reviewdb"
    compileSdk {
        version = release(36) { minorApiLevel = 1 }
    }

    defaultConfig {
        applicationId = "dev.mantikafasi.manticord.reviewdb"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures.viewBinding = true
}

dependencies {
    compileOnly(rootProject.extra["microcordSdk"]!!)
    compileOnly(libs.rxjava)
    compileOnly(libs.androidx.recyclerview)
    compileOnly(libs.androidx.appcompat)
    compileOnly(libs.fresco.drawee.span)
    compileOnly(libs.fresco)
    compileOnly(libs.material)
}
