import java.util.Properties

plugins {
    id("com.android.application")
}

// The Android app is a shell around :shared, which holds the app and the trip; this sets the trip's own id and name.
val trip: Properties by rootProject.extra

android {
    namespace = "app.trip.android"
    compileSdk = 36

    defaultConfig {
        applicationId = trip.getProperty("trip.applicationId", "app.trip.planner")
        resValue("string", "app_name", trip.getProperty("trip.appName", "Trip"))
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    implementation(project(":shared"))
}
