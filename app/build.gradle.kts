plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cardprice.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cardprice.app"
        minSdk = 26
        targetSdk = 34
        // Raise versionCode for every copy handed out, so phones accept it as an update.
        versionCode = 3
        versionName = "1.1"
        // Phones run ARM; leaving out the x86 copies of the text-recognition library halves its size.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        manifestPlaceholders["appLabel"] = "Card Companion"
    }

    buildTypes {
        // Beta: installs as a separate app ("Card Companion β", com.cardprice.app.beta) next to the
        // public one, with its own data, so testing never touches the real collection. Debuggable, so
        // its files and scan log can be read over adb.
        debug {
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            manifestPlaceholders["appLabel"] = "Card Companion β"
        }
        release {
            // Smaller download: unused code and resources are stripped.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with this computer's debug key, the same one as the copy already installed on the
            // owner's phone, so the shared APK installs as an update there without losing data.
            signingConfig = signingConfigs.getByName("debug")
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
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Card scanner: camera preview/analysis and on-device text recognition (bundled model, works offline).
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM tests; the Android stub throws.
    testImplementation("org.json:json:20240303")
}
