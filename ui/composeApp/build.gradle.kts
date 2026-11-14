import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

val androidEnabled = gradle.extra["aeon.androidEnabled"] as Boolean
if (androidEnabled) apply(plugin = "com.android.kotlin.multiplatform.library")

kotlin {
    jvm("desktop")

    if (androidEnabled) {
        // The plugin is applied conditionally, so there is no generated accessor: configure its extension by name.
        (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryExtension>("android") {
            namespace = "dev.aeon.ui.shared"
            compileSdk = 37
            minSdk = 26
        }
    }

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
            implementation(compose.components.resources)
            implementation(compose.animation)
            implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
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
        if (androidEnabled) {
            getByName("androidMain").dependencies {
                implementation(libs.ktor.client.cio)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.androidx.activity.compose)
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

compose.resources {
    packageOfResClass = "dev.aeon.ui.generated.resources"
}

compose.desktop {
    application {
        mainClass = "dev.aeon.ui.MainKt"
        nativeDistributions {
            // jpackage builds only for the machine it runs on: Windows x64/ARM64 give msi/exe, Linux gives deb/rpm.
            targetFormats(TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Aeon"
            // The release workflow passes the numeric part of the tag (-PappVersion=1.2.3); jpackage accepts only MAJOR.MINOR.PATCH.
            packageVersion = (findProperty("appVersion") as String?) ?: "0.1.0"
        }
    }
}
