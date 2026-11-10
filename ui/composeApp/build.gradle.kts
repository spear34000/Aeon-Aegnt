import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm("desktop")

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("aeon-ui")
        browser {
            commonWebpackConfig {
                outputFileName = "aeon-ui.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            // Plugin accessors resolve the matching Material3 version (it is versioned separately from Compose).
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        val desktopMain = getByName("desktopMain").apply {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.ktor.client.cio)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        val desktopTest = getByName("desktopTest").apply {
            dependencies {
                implementation(libs.ktor.client.cio)
            }
        }
        wasmJsMain.dependencies {
            implementation(libs.ktor.client.js)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Lets the integration tests start the real Node server from the repository root.
    systemProperty("aeon.repoRoot", rootDir.parentFile.absolutePath)
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

compose.desktop {
    application {
        mainClass = "dev.aeon.ui.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Aeon"
            packageVersion = "0.1.0"
        }
    }
}
