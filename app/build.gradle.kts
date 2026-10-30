import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Which trip to build: -PtripDir=<folder>, TRIP_DIR, or trip.dir in local.properties; else the example in trip/.
// A trip folder holds MyTrip.kt and an optional trip.properties (app id, name, private values), whose
// settings override the machine-wide local.properties (SDK path, API key).
fun props(f: File) = Properties().apply { f.takeIf { it.exists() }?.inputStream()?.use(::load) }
val machine = props(rootProject.file("local.properties"))
val tripDir = file(
    (findProperty("tripDir") as String?) ?: System.getenv("TRIP_DIR") ?: machine.getProperty("trip.dir") ?: rootProject.file("trip").path,
)
require(File(tripDir, "MyTrip.kt").exists()) { "No MyTrip.kt in $tripDir" }
val local = Properties().apply { putAll(machine); putAll(props(File(tripDir, "trip.properties"))) }

android {
    sourceSets.getByName("main").java.srcDir(tripDir)
    namespace = "app.trip"
    compileSdk = 36

    defaultConfig {
        applicationId = local.getProperty("trip.applicationId", "app.trip.planner")
        resValue("string", "app_name", local.getProperty("trip.appName", "Trip"))
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "AVIATIONSTACK_KEY", "\"${local.getProperty("aviationstack.key", "")}\"")
        // trip.private.* values (booking refs, membership numbers…) as "key=value" lines, read by secret() in Private.kt.
        val private = local.stringPropertyNames().filter { it.startsWith("trip.private.") }.sorted()
            .joinToString("\n") { "${it.removePrefix("trip.private.")}=${local.getProperty(it)}" }
        buildConfigField("String", "TRIP_PRIVATE", "\"" + private.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
