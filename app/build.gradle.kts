import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release signing key lives outside the repo, in ~/.config/localbeat/
// (keystore.properties + localbeat-release.jks). Back both up: every phone's
// copy can only be updated by an APK signed with this same key.
val keystoreProperties = Properties().apply {
    val file = File(System.getProperty("user.home"), ".config/localbeat/keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

android {
    namespace = "com.jimboyo.localbeat"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.jimboyo.localbeat"
        // Personal app for a Pixel 9; 33 is the first release with READ_MEDIA_AUDIO.
        minSdk = 33
        targetSdk = 36
        versionCode = 19
        versionName = "0.9.10"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    // Two editions from the same code (docs/remote-access.md, "Decided"):
    // offline has no INTERNET permission and no network code at all; connected
    // adds useful, non-spyware network features (first: the remote server).
    // Same package and key, so installing one over the other keeps the library
    // and settings. Network code lives in src/connected only.
    flavorDimensions += "edition"
    productFlavors {
        create("offline") {
            dimension = "edition"
            isDefault = true
            buildConfigField("boolean", "CONNECTED", "false")
        }
        create("connected") {
            dimension = "edition"
            versionNameSuffix = "-connected"
            buildConfigField("boolean", "CONNECTED", "true")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Without the key (another computer), fall back to the debug key.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
}

dependencies {
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-session:$media3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0")

    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in JVM unit tests; the real one for the Subsonic parsing tests.
    testImplementation("org.json:json:20240303")
}

// `./gradlew dist`: release APKs of both editions, named after the version, in dist/:
// LocalBeat-<version>.apk (offline) and LocalBeat-Connected-<version>.apk.
tasks.register<Copy>("dist") {
    dependsOn("assembleOfflineRelease", "assembleConnectedRelease")
    val version = android.defaultConfig.versionName
    from(layout.buildDirectory.file("outputs/apk/offline/release/app-offline-release.apk")) {
        rename { "LocalBeat-$version.apk" }
    }
    from(layout.buildDirectory.file("outputs/apk/connected/release/app-connected-release.apk")) {
        rename { "LocalBeat-Connected-$version.apk" }
    }
    into(rootProject.layout.projectDirectory.dir("dist"))
}
