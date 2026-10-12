plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rostik.ai"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rostik.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Real Android inference runtime. This is an engine, not a trained Brawl Stars model.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
}
