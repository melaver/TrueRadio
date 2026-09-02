// Top-level build file
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // Firebase Crashlytics: reports every uncaught exception (and anything explicitly recorded
    // via recordException) to the Firebase Console, so a crash can be read from a real stack
    // trace there instead of needing adb/logcat access to the device it happened on.
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.firebase.crashlytics") version "3.0.2" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
