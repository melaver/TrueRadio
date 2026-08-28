plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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

    buildTypes {
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
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Networking (Retrofit + OkHttp) for Gemini / ElevenLabs / RSS
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // XML/RSS parsing
    implementation("org.jsoup:jsoup:1.17.2") // tolerant XML/HTML parsing used for RSS

    // Media3 / ExoPlayer for local ducked TTS playback
    implementation("androidx.media3:media3-exoplayer:1.4.0")
    implementation("androidx.media3:media3-common:1.4.0")
    implementation("androidx.media3:media3-session:1.4.0")

    // Spotify App Remote SDK (place spotify-app-remote-release-x.x.x.aar in app/libs,
    // see README for download instructions from developer.spotify.com)
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // DataStore for storing API keys locally
    implementation("androidx.datastore:datastore-preferences:1.1.1")
}
