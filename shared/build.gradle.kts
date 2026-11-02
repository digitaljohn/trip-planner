import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The app for both platforms, plus the trip being built (see the root build file for which).
val tripDir: File by rootProject.extra
val trip: Properties by rootProject.extra

// The trip folder's .kt files. Trips written for the Android-only app import java.time types, which
// don't exist on iOS; those import lines are blanked (keeping line numbers) and the same names resolve
// to the aliases in Time.kt instead.
val tripSources = tasks.register<Sync>("tripSources") {
    from(tripDir) {
        include("**/*.kt")
        exclude("out/**")
    }
    into(layout.buildDirectory.dir("generated/trip"))
    val javaTime = Regex("""\s*import java\.time\.(Instant|LocalDate|ZoneId|Duration)\s*""")
    filter { line: String -> if (javaTime.matches(line)) "" else line }
}

// Build-time values: the Aviationstack key, and the trip.private.* values (booking refs, membership
// numbers…) as "key=value" lines, read by secret() in Private.kt.
val tripBuild = tasks.register("tripBuild") {
    val out = layout.buildDirectory.dir("generated/tripBuild")
    val key = trip.getProperty("aviationstack.key", "")
    val private = trip.stringPropertyNames().filter { it.startsWith("trip.private.") }.sorted()
        .joinToString("\n") { "${it.removePrefix("trip.private.")}=${trip.getProperty(it)}" }
    inputs.property("key", key)
    inputs.property("private", private)
    outputs.dir(out)
    doLast {
        fun lit(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("\n", "\\n") + "\""
        val f = out.get().file("app/trip/TripBuild.kt").asFile
        f.parentFile.mkdirs()
        f.writeText(
            "package app.trip\n\ninternal object TripBuild {\n" +
                "    const val AVIATIONSTACK_KEY = ${lit(key)}\n" +
                "    const val TRIP_PRIVATE = ${lit(private)}\n}\n",
        )
    }
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(tripSources)
            kotlin.srcDir(tripBuild)
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
            }
        }
        androidMain.dependencies {
            // Pins Jetpack Compose to the versions the Android-only app shipped with.
            implementation(project.dependencies.platform("androidx.compose:compose-bom:2025.06.00"))
            implementation("androidx.activity:activity-compose:1.10.1")
            implementation("androidx.core:core-ktx:1.16.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.resources {
    packageOfResClass = "app.trip.res"
}

android {
    namespace = "app.trip"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
