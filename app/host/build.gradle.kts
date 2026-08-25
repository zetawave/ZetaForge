import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Injects zetaforge.permissions into the merged manifest: plugins declare
    // what they need, the Host declares the ceiling, nobody edits XML.
    id("com.zetaforge.host-permissions")
}

/**
 * Release signing is configured from `keystore.properties`, which is git-ignored
 * and never committed. Create it with `./scripts/make-keystore` (or by hand):
 *
 *     storeFile=keystore/zetaforge-release.jks
 *     storePassword=...
 *     keyAlias=zetaforge
 *     keyPassword=...
 *
 * `storeFile` may be absolute or relative to the repository root. When the file
 * is absent, debug builds keep working and `assembleRelease` produces an
 * unsigned APK instead of failing at configuration time.
 */
/**
 * The architectures a split is produced for, in the order their versionCode
 * offsets are assigned. Never reorder: an offset that moves would make a new
 * release look older than the one it replaces.
 */
val ABI_FILTERS = listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.isFile) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val releaseKeystore: File? = keystoreProperties.getProperty("storeFile")
    ?.let { path -> File(path).takeIf { it.isAbsolute } ?: rootProject.file(path) }
    ?.takeIf { it.isFile }

android {
    namespace = rootProject.extra["zetaforge.host.package"] as String
    compileSdk = (rootProject.extra["zetaforge.compileSdk"] as String).toInt()

    defaultConfig {
        applicationId = rootProject.extra["zetaforge.host.package"] as String
        minSdk = (rootProject.extra["zetaforge.minSdk"] as String).toInt()
        targetSdk = (rootProject.extra["zetaforge.targetSdk"] as String).toInt()
        versionCode = (rootProject.extra["zetaforge.versionCode"] as String).toInt()
        versionName = rootProject.extra["zetaforge.versionName"] as String

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates the adb-driven developer hooks in MainActivity.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("androidTest") {
            // The instrumented acceptance test consumes the real .zeta artifact,
            // copied here by the `copyDemoPluginAsset` task below.
            assets.srcDir(layout.buildDirectory.dir("generated/zetaAssets"))
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }

    /**
     * One APK per ABI, plus a universal one that carries them all.
     *
     * The Host itself is pure Kotlin; the only native code in the package comes
     * from a dependency (`libandroidx.graphics.path.so`, about 10 KB per ABI).
     * Splitting therefore saves very little - see the release notes the build
     * prints - and the universal APK stays the one to hand to somebody who does
     * not know their device's architecture.
     */
    splits {
        abi {
            isEnable = true
            reset()
            include(*ABI_FILTERS.toTypedArray())
            isUniversalApk = true
        }
    }
}

/**
 * A distinct versionCode per ABI, following Google's recipe: the universal APK
 * keeps the base code and each split sits above it, so a device offered both
 * prefers the one built for it. Without this every split would carry the same
 * code and Android would refuse to update one with another.
 */
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
            val offset = ABI_FILTERS.indexOf(abi) + 1
            val base = (rootProject.extra["zetaforge.versionCode"] as String).toInt()
            output.versionCode.set(offset * 1_000_000 + base)
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":runtime"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.splashscreen)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

/**
 * Makes the freshly built `retrofit-demo.zeta` available to the instrumented
 * acceptance test as an asset. This is the only link between Host and plugin,
 * and it exists purely for testing: at runtime the Host knows nothing about any
 * specific plugin.
 */
val demoPluginArtifact = tasks.register<Copy>("copyDemoPluginAsset") {
    val pluginModules = listOf(":plugins:retrofit-demo", ":plugins:files-demo")
    pluginModules.forEach { dependsOn("$it:buildZetaPlugin") }
    pluginModules.forEach { path ->
        from(project(path).layout.buildDirectory.dir("zetaforge")) { include("*.zeta") }
    }
    into(layout.buildDirectory.dir("generated/zetaAssets"))
}

tasks.withType<com.android.build.gradle.tasks.MergeSourceSetFolders>().configureEach {
    if (name.contains("AndroidTest", ignoreCase = true)) {
        dependsOn(demoPluginArtifact)
    }
}

/**
 * Verifies requirement #29: Retrofit/OkHttp must NOT be part of the Host APK.
 * The check inspects the DEX of the built APK, so it fails if the dependency is
 * ever added by mistake (directly or transitively).
 */
val verifyHostApk = tasks.register("verifyHostHasNoRetrofit") {
    group = "verification"
    description = "Fails if the Host APK contains Retrofit/OkHttp classes."

    val apkDir = layout.buildDirectory.dir("outputs/apk/debug")
    val reportFile = layout.buildDirectory.file("reports/zetaforge/host-apk-verification.txt")
    outputs.file(reportFile)

    doLast {
        val apk = apkDir.get().asFile.listFiles()?.firstOrNull { it.extension == "apk" }
            ?: throw GradleException("No Host APK found in ${apkDir.get().asFile}. Run assembleDebug first.")

        val forbidden = listOf("Lretrofit2/", "Lokhttp3/", "Lokio/")
        val found = mutableListOf<String>()
        ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .forEach { entry ->
                    val bytes = zip.getInputStream(entry).use { it.readBytes() }
                    val text = String(bytes, Charsets.ISO_8859_1)
                    forbidden.forEach { marker ->
                        if (text.contains(marker)) found += "${entry.name}: $marker"
                    }
                }
        }

        val report = buildString {
            appendLine("ZetaForge Host APK verification")
            appendLine("apk      : ${apk.absolutePath}")
            appendLine("size     : ${apk.length()} bytes")
            appendLine("forbidden: ${forbidden.joinToString()}")
            appendLine(if (found.isEmpty()) "result   : PASS - no plugin-only library found in the Host APK"
            else "result   : FAIL - ${found.joinToString()}")
        }
        reportFile.get().asFile.parentFile.mkdirs()
        reportFile.get().asFile.writeText(report)
        logger.lifecycle("\n$report")

        if (found.isNotEmpty()) {
            throw GradleException("Host APK must not contain plugin-only libraries: ${found.joinToString()}")
        }
    }
}

// AGP registers `assembleDebug` late, so match lazily instead of resolving now.
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(verifyHostApk) }
