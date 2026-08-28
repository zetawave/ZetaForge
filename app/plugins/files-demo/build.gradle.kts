plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.zetaforge.zeta-plugin")
}

/**
 * Second reference plugin: proves the runtime-permission path end to end.
 *
 * It touches shared media storage, which on Android is a *dangerous* permission
 * that must be granted by the user at run time. The Host declares it in its
 * superset (`zetaforge.permissions`), this plugin declares that it needs it, and
 * the runtime asks for it at START.
 */
android {
    namespace = "com.zetaforge.plugins.filesdemo"
    compileSdk = (rootProject.extra["zetaforge.compileSdk"] as String).toInt()

    defaultConfig {
        applicationId = "com.zetaforge.plugins.filesdemo"
        minSdk = (rootProject.extra["zetaforge.minSdk"] as String).toInt()
        targetSdk = (rootProject.extra["zetaforge.targetSdk"] as String).toInt()
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Shared contract and Kotlin runtime: provided by the Host, never bundled.
    compileOnly(project(":plugin-api"))
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.kotlinx.coroutines.core)
    // No third-party dependency here on purpose: this plugin only uses the
    // Android framework, which is the other half of the experiment.
}

zetaPlugin {
    pluginId.set("com.zetaforge.plugins.filesdemo")
    displayName.set("Files Demo")
    version.set("1.0.0")
    author.set("ZetaForge Team <plugins@example.com>")
    homepage.set("https://example.com/zetaforge/files-demo")
    license.set("Apache-2.0")
    description.set(
        "Reads the device media library through MediaStore and writes a small " +
            "report into the Host's private storage. Demonstrates a plugin that " +
            "needs a run-time permission."
    )
    entryPoint.set("com.zetaforge.plugins.filesdemo.FilesDemoPlugin")
    minHostApi.set(1)
    maxHostApi.set(5)

    // API 33+ : granular media permission.
    permission("android.permission.READ_MEDIA_IMAGES") {
        reason = "Counts the images in your library to build the report"
        minSdk = 33
    }
    // API 32 and below: the legacy storage permission covers the same query.
    permission("android.permission.READ_EXTERNAL_STORAGE") {
        reason = "Counts the images in your library to build the report"
        maxSdk = 32
    }
    // Optional: the plugin still runs without it, with less detail.
    permission("android.permission.ACCESS_MEDIA_LOCATION") {
        reason = "Reads the location stored inside photos, when available"
        optional = true
        minSdk = 29
    }

    capabilities.set(listOf("storage.read", "storage.write"))
    archiveBaseName.set("files-demo")
}

tasks.withType<com.zetaforge.builder.BuildZetaPluginTask>().configureEach {
    minSdk.set((rootProject.extra["zetaforge.minSdk"] as String).toInt())
    manifestFormatVersion.set((rootProject.extra["zetaforge.manifestFormatVersion"] as String).toInt())
    hostProvidedDependencies.set(
        listOf(
            "com.zetaforge:plugin-api:" + (rootProject.extra["zetaforge.hostApiVersion"] as String),
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
        )
    )
}
