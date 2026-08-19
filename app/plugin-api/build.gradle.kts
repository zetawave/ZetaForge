plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = rootProject.extra["zetaforge.sdk.package"] as String
    compileSdk = (rootProject.extra["zetaforge.compileSdk"] as String).toInt()

    defaultConfig {
        minSdk = (rootProject.extra["zetaforge.minSdk"] as String).toInt()
        consumerProguardFiles("consumer-rules.pro")
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
    // The SDK is the only artifact shared between Host and plugins, so it must
    // stay as small as possible: Kotlin stdlib + coroutines, nothing else.
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.kotlinx.coroutines.core)
}

/**
 * Produces `zetaforge-api-<hostApi>.jar`: the contract, and nothing else.
 *
 * This is the single artifact shared between the Host and every plugin. It ships
 * inside the `zeta` CLI on npm, so a plugin author never resolves it from a
 * repository — the jar and the Host that implements it come out of this same
 * build, and therefore cannot drift apart.
 */
val apiJar by tasks.registering(Copy::class) {
    group = "zetaforge"
    description = "Extracts the plugin contract as a plain jar for the zeta CLI."
    dependsOn("assembleRelease")

    val hostApi = rootProject.extra["zetaforge.hostApiVersion"] as String
    val aar = layout.buildDirectory.file("outputs/aar/plugin-api-release.aar")

    from(zipTree(aar)) { include("classes.jar") }
    into(rootProject.layout.buildDirectory.dir("zetaforge/sdk"))
    rename { "zetaforge-api-$hostApi.jar" }

    doLast {
        val out = rootProject.layout.buildDirectory
            .file("zetaforge/sdk/zetaforge-api-$hostApi.jar").get().asFile
        logger.lifecycle("contract -> " + out.absolutePath + " (" + out.length() + " bytes)")
    }
}
