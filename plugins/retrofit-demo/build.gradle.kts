plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.zetaforge.zeta-plugin")
}

/**
 * This module is built and versioned completely independently of the Host.
 * It is an Android application module only because that is the cleanest way to
 * get the real Android pipeline (Kotlin -> class files -> D8 -> DEX) including
 * its own external dependencies. The resulting APK is never installed: the
 * `buildZetaPlugin` task harvests its DEX into a `.zeta` archive.
 */
android {
    namespace = "com.zetaforge.plugins.retrofitdemo"
    compileSdk = (rootProject.extra["zetaforge.compileSdk"] as String).toInt()

    defaultConfig {
        applicationId = "com.zetaforge.plugins.retrofitdemo"
        minSdk = (rootProject.extra["zetaforge.minSdk"] as String).toInt()
        targetSdk = (rootProject.extra["zetaforge.targetSdk"] as String).toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Shrinking would remove the entry point, which nothing references
            // statically: the Host resolves it by name at runtime.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "DebugProbesKt.bin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // --- Provided by the Host at runtime: must NOT end up in the plugin DEX ---
    // These are the shared contract; bundling them would create duplicate class
    // objects and break every cast across the boundary.
    compileOnly(project(":plugin-api"))
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.kotlinx.coroutines.core)

    // --- The plugin's own dependencies: these DO end up in the plugin DEX ---
    // The Kotlin stdlib is excluded from their transitive graph: OkHttp/Okio are
    // written in Kotlin, but the Host already ships a (newer, binary compatible)
    // stdlib and it must remain a single shared copy across the boundary.
    implementation(libs.retrofit) { exclude(group = "org.jetbrains.kotlin") }
    implementation(libs.okhttp) { exclude(group = "org.jetbrains.kotlin") }
}

zetaPlugin {
    pluginId.set("com.zetaforge.plugins.retrofitdemo")
    displayName.set("Retrofit Demo")
    version.set("0.1.0")
    description.set("Performs a real HTTPS GET with Retrofit + OkHttp bundled in the plugin itself.")
    author.set("ZetaForge")
    entryPoint.set("com.zetaforge.plugins.retrofitdemo.RetrofitDemoPlugin")
    minHostApi.set(1)
    maxHostApi.set(1)
    permissions.set(listOf("android.permission.INTERNET"))
    capabilities.set(listOf("network.http"))
    archiveBaseName.set("retrofit-demo")
}

// The task is registered from the AGP variant callback, so configure it lazily by type.
tasks.withType<com.zetaforge.builder.BuildZetaPluginTask>().configureEach {
    minSdk.set((rootProject.extra["zetaforge.minSdk"] as String).toInt())
    manifestFormatVersion.set((rootProject.extra["zetaforge.manifestFormatVersion"] as String).toInt())
    bundledDependencies.set(
        providers.provider {
            configurations.getByName("releaseRuntimeClasspath")
                .resolvedConfiguration.lenientConfiguration.allModuleDependencies
                .map { "${it.moduleGroup}:${it.moduleName}:${it.moduleVersion}" }
                .sorted()
        }
    )
    hostProvidedDependencies.set(
        listOf(
            "com.zetaforge:plugin-api:" + (rootProject.extra["zetaforge.hostApiVersion"] as String),
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
        )
    )
}
