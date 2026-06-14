import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.sqlDelight)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }
    js(IR) { browser() }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(libs.sqlDelight.runtime)
                api(libs.sqlDelight.coroutines)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        // Shared web source set inherited by both wasmJs (primary) and js (fallback).
        val webMain by creating {
            dependsOn(commonMain)
            dependencies {
                api(libs.sqlDelight.driver.js)
            }
        }
        val wasmJsMain by getting { dependsOn(webMain) }
        val jsMain by getting { dependsOn(webMain) }
    }
}

sqldelight {
    databases {
        create("AcronymizerWebDb") {
            packageName.set("io.github.kdroidfilter.seforim.acronymizer.webdb")
            // Reuse the exact same schema/queries as the JVM tool, no duplication.
            srcDirs(rootProject.file("acronymizer/src/commonMain/sqldelight"))
            generateAsync.set(true)
            dialect("app.cash.sqldelight:sqlite-3-24-dialect:${libs.versions.sqlDelight.get()}")
        }
    }
}
