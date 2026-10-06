plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.room")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
    }
    // iOS targets: compiled by the macOS build (GitHub Actions / Codemagic); Gradle skips them on Windows.
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            api("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
            api("io.insert-koin:koin-core:4.0.4")
            api("io.insert-koin:koin-compose:4.0.4")
            api("io.insert-koin:koin-compose-viewmodel:4.0.4")
            api("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
            api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.8.2")
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.ui)
            api(compose.materialIconsExtended)
            api("androidx.room:room-runtime:2.7.2")
            implementation("androidx.sqlite:sqlite-bundled:2.5.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            api("io.ktor:ktor-client-core:3.0.3")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:3.0.3")
            // Only for the @DocumentId / @PropertyName typealiases while Firestore is still in use; goes away with the backend switch.
            implementation(project.dependencies.platform("com.google.firebase:firebase-bom:33.3.0"))
            implementation("com.google.firebase:firebase-firestore-ktx")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.0.3")
        }
        commonTest.dependencies {
            implementation("io.ktor:ktor-client-mock:3.0.3")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.emfitsolutions.gopreach.shared"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    add("kspAndroid", "androidx.room:room-compiler:2.7.2")
    add("kspIosX64", "androidx.room:room-compiler:2.7.2")
    add("kspIosArm64", "androidx.room:room-compiler:2.7.2")
    add("kspIosSimulatorArm64", "androidx.room:room-compiler:2.7.2")
}
