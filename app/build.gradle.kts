import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("com.google.devtools.ksp")
}

// TomTom map tiles experiment — the key lives only in local.properties
// (gitignored, never committed) and is exposed to Kotlin as a BuildConfig
// constant rather than hardcoded in source, same reasoning as any other
// per-developer secret. Note this does NOT make the key safe from
// extraction: it still ends up embedded in plain text inside the WebView
// HTML string the app loads at runtime, readable by anyone who inspects
// network traffic or decompiles the APK — there is no way to fully hide a
// client-side map-tile key, this is just "don't also leak it via git history."
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) localPropertiesFile.inputStream().use { load(it) }
}
val tomtomApiKey: String = localProperties.getProperty("tomtomApiKey", "")
val backendUrl: String = localProperties.getProperty("gopreach.backendUrl", "")

// MapTiler's "Dark" basemap (Territory Assignment's Barangay Boundary map,
// Night mode) — same per-developer-secret reasoning as the TomTom key above:
// lives only in local.properties (gitignored), not safe from extraction once
// embedded in a running app, just kept out of git history.
val mapTilerApiKey: String = localProperties.getProperty("mapTilerApiKey", "")

// Mapillary (free street-level photos for the Street View option on the maps): a
// "Client Token" from mapillary.com/dashboard/developers, kept only in
// local.properties as `mapillaryToken=MLY|...` (gitignored). Blank = Street View
// tells the user it is not set up instead of failing.
val mapillaryToken: String = localProperties.getProperty("mapillaryToken", "")

android {
    namespace = "com.emfitsolutions.gopreach"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.emfitsolutions.gopreach"
        minSdk = 24
        targetSdk = 36
        versionCode = 196
        versionName = "1.135.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
        // Still consumed by a few raster tile URLs elsewhere (Share Location,
        // Territory Map's satellite layer, Location Preview) — the native
        // TomTom Maps SDK itself is no longer a dependency of this app (see
        // dependencies block below); Territory Assignment's Barangay
        // Boundary map now renders entirely on free/keyless OpenStreetMap-
        // backed tiles.
        buildConfigField("String", "TOMTOM_API_KEY", "\"$tomtomApiKey\"")
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        buildConfigField("String", "MAPTILER_API_KEY", "\"$mapTilerApiKey\"")
        buildConfigField("String", "MAPILLARY_TOKEN", "\"$mapillaryToken\"")
    }

    // Release signing. A production build is signed with GoPreach's own release key, read from
    // keystore.properties (gitignored, never committed -- see SETUP.md "Release signing"):
    //   storeFile=C:/path/to/gopreach-release.jks   storePassword=...   keyAlias=...   keyPassword=...
    // Debug builds always use the standard debug key. Until a release keystore exists the release
    // build falls back to the debug key so existing installs keep updating -- and says so loudly.
    val keystoreProperties = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val hasReleaseKey = keystoreProperties.getProperty("storeFile")?.let { file(it).exists() } == true
    val debugKeystore = file(System.getProperty("user.home") + "/.android/debug.keystore")

    signingConfigs {
        getByName("debug") {
            storeFile = debugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("WARNING: keystore.properties not found -- the release build is signed with the DEBUG key. Do not distribute it as a production build.")
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
            // No applicationIdSuffix: debug and release share one package ID
            // (and one signing key) so installing either build updates the
            // existing app instead of adding a second copy on the phone.
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Output "GoPreach.apk" instead of Gradle's default "app-release.apk" /
    // "app-debug.apk" naming -- both variants share the name (they land in
    // separate outputs/apk/<debug|release>/ folders so there's no clash) so
    // every GitHub release always ships an asset literally named
    // "GoPreach.apk", regardless of which build type actually produced it.
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "GoPreach.apk"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core / Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    // AppCompatActivity — MainActivity extends it for androidx.biometric.BiometricPrompt's
    // FragmentManager requirement (see MainActivity.kt's own comment).
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Biometric sign-in + encrypted "remember me" credential storage (login screen).
    implementation("androidx.fragment:fragment-ktx:1.8.4")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Hilt
    implementation(platform("io.insert-koin:koin-bom:4.0.4"))
    implementation("io.insert-koin:koin-android")
    implementation("io.ktor:ktor-client-okhttp:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.insert-koin:koin-compose")
    implementation("io.insert-koin:koin-compose-viewmodel")
    implementation("io.insert-koin:koin-androidx-workmanager")

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.3.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Room (offline cache)
    implementation("androidx.room:room-runtime:2.7.0")
    implementation("androidx.room:room-ktx:2.7.0")
    ksp("androidx.room:room-compiler:2.7.0")

    // WorkManager (offline sync queue)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Location / Maps (Share Location, GPS capture)
    implementation("com.google.android.gms:play-services-location:21.3.0")


    // MapLibre Native — vector maps with tilt/rotate and 3D buildings. Replaces
    // osmdroid and the Leaflet WebViews stage by stage (restore point: git tag
    // `restore-before-maplibre`).
    implementation("org.maplibre.gl:android-sdk:11.13.5")

    // Coil (logo / image loading)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // JSON for the offline cache/outbox payloads (see data/local)
    implementation("com.google.code.gson:gson:2.11.0")
    // Kotlin Multiplatform shared module (geometry now; models, rules and UI move here step by step).
    implementation(project(":shared"))

    // Testing
    testImplementation("io.insert-koin:koin-test")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
