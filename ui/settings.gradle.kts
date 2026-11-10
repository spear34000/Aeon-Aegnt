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

// Android (androidApp + androidMain actuals) is added once an Android SDK exists on the machine.
include(":composeApp")
