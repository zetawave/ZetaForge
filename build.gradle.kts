import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// ---------------------------------------------------------------------------
// Product identity is centralised in zetaforge.properties and exposed to every
// module through `rootProject.extra`, so no identifier is hardcoded twice.
// ---------------------------------------------------------------------------
val zetaProps = Properties().apply {
    rootProject.file("zetaforge.properties").inputStream().use { load(it) }
}
zetaProps.forEach { (k, v) -> extra[k.toString()] = v.toString() }

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
