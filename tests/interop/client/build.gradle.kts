// Runs the Android app's real protocol + SyncClient/SyncHub code on a plain JVM,
// with tiny stand-ins for the few Android classes they touch (src/main/kotlin/stubs).
plugins {
    kotlin("jvm") version "2.0.21"
    application
}

repositories { mavenCentral() }

sourceSets {
    main {
        kotlin {
            srcDir("../../../android/app/src/main/java")
            // Only the platform-neutral app files; everything Android-specific is stubbed.
            include(
                "app/seamlessclip/protocol/**",
                "app/seamlessclip/net/SyncClient.kt",
                "app/seamlessclip/net/SyncHub.kt",
                "stubs/**",
                "harness/**",
            )
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.json:json:20240303")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { targetCompatibility = JavaVersion.VERSION_17 }

application { mainClass.set("harness.MainKt") }
