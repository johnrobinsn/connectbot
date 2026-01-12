@file:Suppress("ktlint:standard:property-naming")

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
    }
}

// Composite build: use local termlib if available (for development)
if (file("../termlib").exists()) {
    includeBuild("../termlib") {
        dependencySubstitution {
            substitute(module("com.github.johnrobinsn:termlib")).using(project(":lib"))
        }
    }
}

val TRANSLATIONS_ONLY: String? by settings

if (TRANSLATIONS_ONLY.isNullOrBlank()) {
    include(":app")
}
include(":translations")
