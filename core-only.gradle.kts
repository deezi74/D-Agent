// Root build used when no Android SDK is installed: only the pure-Kotlin :core module.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
