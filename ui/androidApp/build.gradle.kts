plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val nodeMobile = file("nodejs-mobile")

android {
    namespace = "dev.aeon.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.aeon.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
