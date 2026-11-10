rootProject.name = "aeon-ui"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// Android is optional at configuration time: it is included only when an Android SDK is found, so the desktop and
// web builds keep working on machines without one.
fun androidSdkDir(): String? {
    val local = file("local.properties")
    val fromLocal = if (local.exists()) java.util.Properties().apply { local.inputStream().use { load(it) } }.getProperty("sdk.dir") else null
    return fromLocal ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
}
val androidEnabled = androidSdkDir()?.let { file(it).isDirectory } == true
gradle.extra["aeon.androidEnabled"] = androidEnabled

include(":composeApp")
if (androidEnabled) include(":androidApp")
