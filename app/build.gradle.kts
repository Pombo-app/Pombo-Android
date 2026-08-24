import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

// Release signing credentials, read from the Gradle home (~/.gradle/
// gradle.properties) or, as a fallback, from the gitignored local.properties.
// Prefer the Gradle home: it keeps the password outside the project folder, so
// copying or archiving the checkout cannot carry it along. The keystore itself
// lives outside the repository either way.
//
// A checkout without them still builds — the release variant falls back to the
// debug key, which is enough to install and run, but not to publish or to
// match the fingerprint in the site's assetlinks.json.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingProp(name: String): String? =
    (findProperty(name) as String?) ?: localProps.getProperty(name)

val releaseStoreFile = signingProp("POMBO_RELEASE_STORE_FILE")
    ?.let { rootProject.file(it) }
    ?.takeIf { it.exists() }

android {
    namespace = "com.pombo.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pombo.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = signingProp("POMBO_RELEASE_STORE_PASSWORD")
                keyAlias = signingProp("POMBO_RELEASE_KEY_ALIAS")
                keyPassword = signingProp("POMBO_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // The JS bridge depends on exact global names; no obfuscation.
            isMinifyEnabled = false
            // An unsigned release APK is refused by the device, so the variant
            // always carries a signingConfig: the real key when this machine
            // has it, the debug key otherwise so the variant still runs from
            // Android Studio.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
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
    // FCM: receives the relay wake signals as data-only messages.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
    // Full Bouncy Castle for the dead-app push path ONLY: opening a sealed-
    // sender DM envelope needs secp256k1 ECDH + keccak256 + ecrecover, none of
    // which Android's stripped BC has, and the WebView bridge is not running
    // when FCM wakes a dead process. Used via direct class references (no JCA
    // provider registration), byte-parity locked by SealedSenderCryptoTest
    // vectors generated with the web's own ethers.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // Real org.json for JVM unit tests — the Android stub throws "not mocked".
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Encrypted private-key storage (equivalent of the web's secureStorage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // Device-credential gate before revealing or destroying the key. The web
    // asks for the keystore password; this app has no such password, so the
    // device lock is the equivalent proof of ownership.
    implementation("androidx.biometric:biometric:1.1.0")

    // Deterministic SVG avatars (same output as the web's AvatarGenerator)
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    // Animated GIF/WebP playback in chat (the web just uses <img>, which animates).
    implementation("io.coil-kt:coil-gif:2.7.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
