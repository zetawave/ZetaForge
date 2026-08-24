plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = rootProject.extra["zetaforge.runtime.package"] as String
    compileSdk = (rootProject.extra["zetaforge.compileSdk"] as String).toInt()

    defaultConfig {
        minSdk = (rootProject.extra["zetaforge.minSdk"] as String).toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":plugin-api"))

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    // The screen contract mentions @Composable, so resolving ZetaUiPlugin needs
    // the annotation on the classpath. Compile-time only: the runtime draws
    // nothing itself, it just recognises a plugin that can.
    compileOnly(platform(libs.compose.bom))
    compileOnly(libs.compose.runtime)

    // org.json ships as stubs in the Android SDK; unit tests need a real one.
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
