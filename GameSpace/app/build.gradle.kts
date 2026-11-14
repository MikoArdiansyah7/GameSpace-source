plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.alya.gamespace"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.alya.gamespace"
        minSdk = 26          // Android 8.0
        targetSdk = 36       // Android 16; jalan juga di 17+
        versionCode = 11
        versionName = "1.9.1"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { aidl = true }
}
dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
