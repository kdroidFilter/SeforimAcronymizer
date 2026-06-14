import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "editor.js"
            }
        }
        binaries.executable()
    }
    js(IR) {
        browser {
            commonWebpackConfig {
                outputFileName = "editor.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.compose.unstyled)
                implementation(project(":db"))
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.json)
            }
        }
        // Shared web code (wasmJs primary + js fallback). Platform glue is in the leaf source sets.
        val webMain by creating {
            dependsOn(commonMain)
            dependencies {
                implementation(libs.ktor.client.js)
            }
        }
        val wasmJsMain by getting {
            dependsOn(webMain)
            dependencies {
                implementation(npm("sql.js", "1.13.0"))
                implementation(npm("@cashapp/sqldelight-sqljs-worker", "2.1.0"))
                implementation(devNpm("copy-webpack-plugin", "9.1.0"))
            }
        }
        val jsMain by getting {
            dependsOn(webMain)
            dependencies {
                implementation(npm("sql.js", "1.13.0"))
                implementation(npm("@cashapp/sqldelight-sqljs-worker", "2.1.0"))
                implementation(devNpm("copy-webpack-plugin", "9.1.0"))
            }
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "io.github.kdroidfilter.seforimacronymizer.editor.resources"
}

// Bundle the canonical dump (repo source of truth) into the app resources so the editor loads
// its base offline — no runtime fetch from the repo. Regenerated on every build/deploy.
val copyDbResource by tasks.registering(Copy::class) {
    from(rootProject.file("data/acronymizer.sql"))
    into(layout.projectDirectory.dir("src/commonMain/composeResources/files"))
}
tasks.matching {
    (it.name.contains("Resources") || it.name.endsWith("BrowserDistribution") ||
        it.name.endsWith("BrowserProductionWebpack") || it.name.endsWith("BrowserDevelopmentWebpack")) &&
        it.name != "copyDbResource"
}.configureEach {
    dependsOn(copyDbResource)
}
