plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val nodeMobile = file("nodejs-mobile")

// A release build (the release workflow, docs/RELEASING.md) passes -PappVersion=1.2.3 -PappVersionCode=10203 and, to sign, the
// AEON_KEYSTORE_* variables. Without them this builds as before: version 0.1.0, and a release build is left unsigned.
val appVersion = (findProperty("appVersion") as String?) ?: "0.1.0"
val appVersionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1
val keystoreFile = System.getenv("AEON_KEYSTORE_FILE")

android {
    namespace = "dev.aeon.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.aeon.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    signingConfigs {
        if (!keystoreFile.isNullOrEmpty()) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("AEON_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("AEON_KEY_ALIAS")
                keyPassword = System.getenv("AEON_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!keystoreFile.isNullOrEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    // The agent that runs on the phone itself (decision D19): Node.js for Mobile plus the compiled server. Built only
    // when nodejs-mobile has been fetched (tools/fetch-nodejs-mobile.mjs); without it the app is the same remote control
    // as before and offers only "내 컴퓨터에 연결".
    if (nodeMobile.exists()) {
        ndkVersion = "29.0.14206865"
        defaultConfig {
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        externalNativeBuild {
            ndkBuild { path = file("src/main/jni/Android.mk") }
        }
        // A plain folder: the task that fills it (syncNodeProject) runs before the build starts (see preBuild below).
        sourceSets["main"].assets.srcDir(file("build/generated/nodejs-assets"))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.coroutines.android)
}

// Compile the server to plain JavaScript for Node 18 and bundle it as an asset. Needs Node 24 on the build machine.
if (nodeMobile.exists()) {
    val repoRoot = rootDir.parentFile
    val buildNodeProject = tasks.register<Exec>("buildNodeProject") {
        workingDir = repoRoot
        commandLine(if (System.getProperty("os.name").lowercase().contains("win")) listOf("cmd", "/c", "npm", "run", "build:mobile") else listOf("npm", "run", "build:mobile"))
    }
    val syncNodeProject = tasks.register<Sync>("syncNodeProject") {
        dependsOn(buildNodeProject)
        from(File(repoRoot, "dist-mobile"))
        into(layout.buildDirectory.dir("generated/nodejs-assets/nodejs-project"))
    }
    tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(syncNodeProject) }
}
