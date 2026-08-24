plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // A screen is written in Compose, so the Compose compiler runs over the
    // plugin's sources exactly as it does over the Host's. Nothing of Compose
    // ends up in the plugin's DEX: every artifact below is compileOnly, and
    // buildZetaPlugin fails the build if one ever is not.
    alias(libs.plugins.kotlin.compose)
    id("com.zetaforge.zeta-plugin")
}

/**
 * Third reference plugin, and the first that is a *screen* rather than a job.
 *
 * It exists to prove the whole screen path end to end with the least possible
 * distraction: no permissions, no network, no storage, nothing to configure. If
 * this calculator works, what worked is the mechanism - the Host's container
 * Activity, the Host's Compose reaching a class loaded from a `.zeta`, the
 * theme, the touch handling and the crash containment - and not anything the
 * plugin itself is clever about.
 */
android {
    namespace = "com.zetaforge.plugins.calculator"
    compileSdk = (rootProject.extra["zetaforge.compileSdk"] as String).toInt()

    defaultConfig {
        applicationId = "com.zetaforge.plugins.calculator"
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
    // Everything here is provided by the Host at run time. Compose is on this
    // list for the same reason the contract is: the Host builds the composition
    // and the plugin adds to it, so both halves must be the same class objects.
    compileOnly(project(":plugin-api"))
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(platform(libs.compose.bom))
    compileOnly(libs.compose.runtime)
    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.ui.graphics)
    compileOnly(libs.compose.foundation)
    compileOnly(libs.compose.material3)
}

zetaPlugin {
    pluginId.set("com.zetaforge.plugins.calculator")
    displayName.set("Calculator")
    version.set("1.0.0")
    author.set("ZetaForge Team <plugins@example.com>")
    homepage.set("https://example.com/zetaforge/calculator")
    license.set("Apache-2.0")
    description.set(
        "A plain calculator, drawn by the plugin and rendered by the Host. The " +
            "reference for plugins that are a screen rather than a job: no " +
            "permissions, no network, no storage - only the screen path itself."
    )
    entryPoint.set("com.zetaforge.plugins.calculator.CalculatorPlugin")
    minHostApi.set(4)
    maxHostApi.set(4)

    // This plugin is its screen. The Host hides RUN and SCHEDULE for it, which
    // is the honest thing to do: there is nothing to run when nobody is looking.
    ui { only = true }

    choiceSetting("precision", "auto") {
        label = "Decimal places"
        description = "How many decimals a result keeps before it is rounded."
        options = listOf("auto" to "As needed", "2" to "2", "4" to "4", "6" to "6")
    }
    switchSetting("thousands", true) {
        label = "Group thousands"
        description = "Show 1 234 567 rather than 1234567."
    }

    archiveBaseName.set("calculator")
}

tasks.withType<com.zetaforge.builder.BuildZetaPluginTask>().configureEach {
    minSdk.set((rootProject.extra["zetaforge.minSdk"] as String).toInt())
    manifestFormatVersion.set((rootProject.extra["zetaforge.manifestFormatVersion"] as String).toInt())
    uiApiVersion.set((rootProject.extra["zetaforge.uiApiVersion"] as String).toInt())
    hostProvidedDependencies.set(
        listOf(
            "com.zetaforge:plugin-api:" + (rootProject.extra["zetaforge.hostApiVersion"] as String),
            "org.jetbrains.kotlin:kotlin-stdlib",
            "org.jetbrains.kotlinx:kotlinx-coroutines-core",
            "androidx.compose:compose-bom",
            "androidx.compose.material3:material3",
        )
    )
}
