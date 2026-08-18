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

// ---------------------------------------------------------------------------
// TEMPLATE - copy this module to start a new plugin.
// Every value below is what the Host shows to the user, so write it for a human.
// ---------------------------------------------------------------------------
zetaPlugin {
    // Reverse-DNS id: unique, stable, also used as the installation directory.
    pluginId.set("com.zetaforge.plugins.retrofitdemo")

    // Shown as the card title.
    displayName.set("Retrofit Demo")

    // Shown next to the name and used to detect updates. Bump on every release.
    version.set("1.0.0")

    // Shown under the name in the plugin details.
    author.set("ZetaForge Team <plugins@example.com>")

    homepage.set("https://example.com/zetaforge/retrofit-demo")
    license.set("Apache-2.0")

    description.set(
        "Reference plugin: performs a real HTTPS GET using Retrofit and OkHttp " +
            "bundled inside the plugin itself, and reports status, duration and " +
            "a preview of the response."
    )

    // The class implementing com.zetaforge.sdk.ZetaPlugin.
    entryPoint.set("com.zetaforge.plugins.retrofitdemo.RetrofitDemoPlugin")

    // Host API range this plugin was built against.
    minHostApi.set(1)
    maxHostApi.set(1)

    // Permissions: declare only what you use, and say why. The Host shows the
    // reason to the user and asks for it at START, every time it is missing.
    permission("android.permission.INTERNET") {
        reason = "Sends the demo HTTPS request to the public echo endpoint"
    }

    // Free-form capability tags, for future Host-side filtering.
    capabilities.set(listOf("network.http"))

    // Output file name: build/zetaforge/<name>.zeta
    archiveBaseName.set("retrofit-demo")

    // Sources shipped in the package so the user can read the code from the app.
    // Defaults to src/main/kotlin - override only if you need to narrow it.
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
