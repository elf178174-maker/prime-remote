// Every module declares its own plugins, with versions, rather than the usual
// "declare at the root with apply false" arrangement.
//
// That is deliberate. The Android plugin has to stay out of the root build so the
// pure-Kotlin :core module can be built and tested without the Android SDK:
//
//     gradle -c settings-core.gradle.kts :core:test
//
// Declaring the Kotlin Android plugin at the root instead would load it in a class
// loader that has no Android plugin in it, and applying it in :app then fails with
// NoClassDefFoundError: com/android/build/gradle/api/BaseVariant.
//
// The cost is a Gradle warning that the Kotlin plugin is loaded in more than one
// subproject. It is expected here, and the build is unaffected.
