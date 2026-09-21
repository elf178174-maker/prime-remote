// The Kotlin plugins are declared here (and applied in the modules) so that Gradle loads
// each of them once for the whole build. The Android plugin is deliberately left out: it
// only exists in :app, which keeps the pure-Kotlin :core module buildable without the
// Android SDK:
//
//     gradle -c settings-core.gradle.kts :core:test
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
