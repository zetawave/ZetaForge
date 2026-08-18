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
