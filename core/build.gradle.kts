import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// No jvmToolchain() on purpose: the module then compiles with whatever JDK runs Gradle
// (17 or newer), which lets `gradle -c settings-core.gradle.kts :core:test` work on any
// machine without the Android SDK. The bytecode target is pinned so the output stays
// loadable by the Android app module.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped") }
}
