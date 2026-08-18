pluginManagement {
    includeBuild("plugin-builder")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ZetaForge"

// --- Core: contract, runtime, host -----------------------------------------
include(":plugin-api")
include(":runtime")
include(":host")

// --- Plugins (built completely separately from the Host) --------------------
include(":plugins:retrofit-demo")
