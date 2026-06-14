rootProject.name = "SeforimAcronymizer"

pluginManagement {
    repositories {
        google {
            content { 
              	includeGroupByRegex("com\\.android.*")
              	includeGroupByRegex("com\\.google.*")
              	includeGroupByRegex("androidx.*")
              	includeGroupByRegex("android.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            content {
              	includeGroupByRegex("com\\.android.*")
              	includeGroupByRegex("com\\.google.*")
              	includeGroupByRegex("androidx.*")
              	includeGroupByRegex("android.*")
            }
        }
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}
include(":db")
include(":editor")
// The JVM enrichment tool depends on the SeforimLibrary composite build.
// Only wire it (and the :acronymizer module that needs it) when present locally,
// so the web modules (:db, :editor) can still build without it.
if (file("../SeforimLibrary").exists()) {
    include(":acronymizer")
    includeBuild("../SeforimLibrary")
}
