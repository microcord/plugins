import com.android.build.api.dsl.ApplicationExtension
import groovy.json.JsonOutput
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application) apply false
}

// The SDK from Microcord's Maven, or the `pluginSdkJar` output of a checkout passed as -PmicrocordDir.
val sdkVersion = libs.versions.microcord.plugin.api.get()
extra["microcordSdk"] = providers.gradleProperty("microcordDir").orNull
    ?.let { files(file(it).resolve("build/plugin-sdk/manticord-plugin-api-$sdkVersion.jar")) }
    ?: "dev.mantikafasi.manticord:plugin-api:$sdkVersion"

// One key signs every plugin, so updates install over each other and the index can pin it. CI passes
// it in; local release builds fall back to the debug key.
val keystore = providers.environmentVariable("PLUGIN_KEYSTORE").orNull?.let(::file)?.takeIf { it.exists() }
val keystorePassword = providers.environmentVariable("PLUGIN_KEYSTORE_PASSWORD").orNull
val signingKeyAlias = providers.environmentVariable("PLUGIN_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("PLUGIN_KEY_PASSWORD").orNull

subprojects {
    plugins.withId("com.android.application") {
        extensions.configure<ApplicationExtension> {
            if (keystore != null) {
                signingConfigs.create("plugins") {
                    storeFile = keystore
                    storePassword = keystorePassword
                    keyAlias = signingKeyAlias
                    keyPassword = signingKeyPassword
                }
            }
            buildTypes.getByName("release") {
                signingConfig = signingConfigs.findByName("plugins") ?: signingConfigs.getByName("debug")
            }
        }
    }
}

/**
 * Writes `build/plugins/metadata.json`: every plugin's index fields except its APK link, hash and
 * size, which `scripts/release.py` fills in for the plugins it builds. Builds nothing itself, so CI
 * can tell which plugins changed version before building any.
 */
tasks.register("pluginMetadata") {
    val modules = subprojects.filter { it.buildFile.exists() }
    val out = layout.buildDirectory.file("plugins/metadata.json")
    doLast {
        val signer = keystore?.let { file ->
            val store = KeyStore.getInstance(KeyStore.getDefaultType())
            FileInputStream(file).use { store.load(it, keystorePassword?.toCharArray()) }
            sha256(store.getCertificate(signingKeyAlias).encoded)
        }
        val plugins = modules.map { module ->
            val plugin = module.extensions.getByName("manticordPlugin")
            fun prop(name: String) = (plugin.javaClass.getMethod(name).invoke(plugin) as Property<*>).get()
            val android = module.extensions.getByType(ApplicationExtension::class.java).defaultConfig
            linkedMapOf(
                "module" to module.name,
                "id" to prop("getId"),
                "packageName" to android.applicationId,
                "name" to module.property("pluginName"),
                "description" to prop("getDescription"),
                "authors" to module.property("pluginAuthors").toString().split(',').map(String::trim),
                "versionCode" to android.versionCode,
                "versionName" to android.versionName,
                "pluginApiMinimum" to prop("getManticordVersionMinimum"),
                "signerSha256" to signer,
                "sourceUrl" to "https://github.com/microcord/plugins/tree/main/${module.name}",
            )
        }
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(JsonOutput.prettyPrint(JsonOutput.toJson(plugins)))
    }
}

fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
