plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.trueradio.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.trueradio.app"
        minSdk = 26 // required by Spotify App Remote SDK
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // A fixed, checked-in debug keystore (app/debug.keystore) is used instead of the default
    // per-machine ~/.android/debug.keystore. This matters because Spotify's Dashboard requires
    // registering the SHA-1 of the signing key your APK is built with - if every machine (or
    // worse, every ephemeral CI run) generated its own random debug key, that SHA-1 would never
    // match and Spotify would reject the connection. Using one committed keystore means local
    // builds, CI builds, and everyone on the team sign with the exact same key, so the SHA-1
    // registered once in the Dashboard stays valid forever. Never do this for a release key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    // Kotlin 1.9.24 uses the old Compose-compiler-as-separate-artifact model, configured via
    // composeOptions rather than the org.jetbrains.kotlin.plugin.compose Gradle plugin (that
    // plugin only exists for Kotlin 2.0.0+, where the compiler moved into the Kotlin repo).
    // 1.5.14 is the Compose compiler version compatible with Kotlin 1.9.24.
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.browser:browser:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material:material-icons-extended")
    // Explicit rather than relying on it coming transitively via activity-compose: this is the
    // first time this codebase's Kotlin has ever actually reached the compiler (both prior CI
    // failures were earlier in the pipeline - Gradle config, then resource linking) - safer to
    // declare it directly than assume a transitive dependency for the rememberSaveable() used
    // in MainActivity.kt.
    implementation("androidx.compose.runtime:runtime-saveable")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Networking (OkHttp) for Gemini / ElevenLabs / RSS / Spotify Web API
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // XML/RSS parsing
    implementation("org.jsoup:jsoup:1.17.2") // tolerant XML/HTML parsing used for RSS

    // Gson: used directly by GeminiClient and TtsManager (request/response JSON), not just as
    // converter-gson's transitive dependency - declared explicitly rather than relying on that.
    implementation("com.google.code.gson:gson:2.11.0")

    // Media3 / ExoPlayer for local ducked TTS playback
    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-common:1.4.0")

    // Spotify App Remote SDK (place spotify-app-remote-release-x.x.x.aar in app/libs,
    // see README for download instructions from developer.spotify.com)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // DataStore for storing API keys locally
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
