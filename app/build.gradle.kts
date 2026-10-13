import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key, kept out of the repository (see SECURITY.md). Without it, release builds are
// signed with this computer's debug key.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use(::load) }
}

// The owner's TCG Vault address (Tailscale), kept out of the public repository: set
// `tcgVault.serverUrl=https://<pc>.<tailnet>.ts.net:8443` in local.properties (git-ignored). Without it the app
// starts with no server and asks for one in Settings.
val localProps = rootProject.file("local.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use(::load) }
}
val defaultServerUrl: String = localProps?.getProperty("tcgVault.serverUrl")?.trim().orEmpty()

android {
    namespace = "com.cardprice.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cardprice.app"
        minSdk = 26
        targetSdk = 34
        // Raise versionCode for every copy handed out, so phones accept it as an update.
        versionCode = 8
        versionName = "2.2.1"
        // Phones run ARM; leaving out the x86 copies of the text-recognition library halves its size.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        manifestPlaceholders["appLabel"] = "Card Companion"
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"" + defaultServerUrl + "\"")
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
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
        // Beta for the phone and over-the-air updates: the same app id and signing key as the debug
        // build (so it updates in place and OTA keeps working), but optimized like release. Compose is
        // several times slower in a debuggable, unshrunk build, which made scrolling choppy.
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            manifestPlaceholders["appLabel"] = "Card Companion β"
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
        release {
            // Smaller download: unused code and resources are stripped.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The private release key when keystore.properties exists; otherwise this computer's debug
            // key (what the copies shared so far were signed with, so they keep updating in place).
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        buildConfig = true
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
    // Installs the bundled baseline profile (src/main/baseline-prof.txt) so startup and scrolling are AOT-compiled.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")
    // Picks each set's colours from its artwork for the collection screen.
    implementation("androidx.palette:palette-ktx:1.0.0")

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
