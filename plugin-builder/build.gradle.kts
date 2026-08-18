plugins {
    `kotlin-dsl`
}

group = "com.zetaforge.builder"

dependencies {
    // AGP is on the consumer's classpath at execution time; we only need its API
    // to hook into the variant model and to read the produced APK artifact.
    compileOnly("com.android.tools.build:gradle:8.9.2")
    implementation("com.google.code.gson:gson:2.11.0")
}

gradlePlugin {
    plugins {
        create("zetaPluginPackager") {
            id = "com.zetaforge.zeta-plugin"
            implementationClass = "com.zetaforge.builder.ZetaPluginPackagerPlugin"
        }
        create("zetaHostPermissions") {
            id = "com.zetaforge.host-permissions"
            implementationClass = "com.zetaforge.builder.HostPermissionsPlugin"
        }
    }
}
