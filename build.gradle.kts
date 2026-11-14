import java.util.Properties

plugins {
    id("com.android.application") version "8.11.1" apply false
    id("com.android.library") version "8.11.1" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
}

// Which trip to build: -PtripDir=<folder>, TRIP_DIR, or trip.dir in local.properties; else the example in trip/.
// A trip folder holds MyTrip.kt and an optional trip.properties (app id, name, private values), whose
// settings override the machine-wide local.properties (SDK path, API key).
fun props(f: File) = Properties().apply { f.takeIf { it.exists() }?.inputStream()?.use(::load) }
val machine = props(file("local.properties"))
val tripDir = file(
    (findProperty("tripDir") as String?) ?: System.getenv("TRIP_DIR") ?: machine.getProperty("trip.dir") ?: file("trip").path,
)
require(File(tripDir, "MyTrip.kt").exists()) { "No MyTrip.kt in $tripDir" }
extra["tripDir"] = tripDir
extra["trip"] = Properties().apply { putAll(machine); putAll(props(File(tripDir, "trip.properties"))) }
