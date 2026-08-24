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
include(":plugins:files-demo")
include(":plugins:calculator")

// --- Private plugins ---------------------------------------------------------
// Anything under plugins-local/ is picked up automatically and is git-ignored,
// so personal plugins never end up in the public repository. The build still
// works for someone who clones without that directory.
file("plugins-local").listFiles()
    ?.filter { it.isDirectory && File(it, "build.gradle.kts").isFile }
    ?.sortedBy { it.name }
    ?.forEach { dir ->
        include(":plugins-local:" + dir.name)
        project(":plugins-local:" + dir.name).projectDir = dir
    }
