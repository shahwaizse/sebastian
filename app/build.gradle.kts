import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The release key (see README, "Releases"): ~/.callstream/keystore.properties, or the file CALLSTREAM_KEYSTORE names.
 * Kept out of the repo. Without it, release builds come out unsigned and release.py refuses them.
 */
val releaseKeys = Properties().apply {
    val f = file(System.getenv("CALLSTREAM_KEYSTORE") ?: "${System.getProperty("user.home")}/.callstream/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "dev.callstream.probe"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.rawaiz.sebastian"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Settings → Support → Report the AI and the transcript's Report post here (bridge/report-worker).
        buildConfigField("String", "REPORT_URL", "\"https://get.shahwaiz.dev/report\"")
        // -PabiOnly=arm64-v8a: a slim APK for one phone family (Pixel 6 Pro and the Redmi are both arm64-v8a).
        (project.findProperty("abiOnly") as String?)?.let { abi -> ndk { abiFilters += abi } }
    }

    // direct: the APK on get.shahwaiz.dev, which updates itself (shell/Updater). play: Google Play, which allows no
    // self-updates, so the updater and REQUEST_INSTALL_PACKAGES live only in src/direct (src/play has stubs).
    flavorDimensions += "store"
    productFlavors {
        create("direct") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "true")
        }
        create("play") {
            dimension = "store"
            buildConfigField("boolean", "SELF_UPDATE", "false")
        }
    }

    signingConfigs {
        if (!releaseKeys.isEmpty) create("release") {
            storeFile = file(releaseKeys.getProperty("storeFile"))
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            // R8 strips unused library code (~58 MB to a few MB); proguard-rules.pro keeps all of ours.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // -PshrinkDebug: R8 on a debug build (strips unused library code, ~58 MB to a few MB) for sending one over chat.
            isMinifyEnabled = project.hasProperty("shrinkDebug")
            if (isMinifyEnabled) proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            applicationIdSuffix = ""
            // Same key as releases when it's on this machine, so a dev phone takes either build over the other.
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // For phones that get the APK through WhatsApp or a browser: Play Protect's enhanced fraud protection blocks such an
        // APK, with no override, when it declares notification access ("App blocked to protect your device", Pixel 6 Pro,
        // 2026-11-05). src/pixel/AndroidManifest.xml removes CallNotificationListener; the helper reads the call's notice
        // itself (CallerFromDump) and hang-up falls back to the call screen. Build: assemblePlayPixel.
        create("pixel") {
            initWith(getByName("debug"))
            matchingFallbacks += "debug"
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
        aidl = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub on the JVM; tests need the real one.
    testImplementation("org.json:json:20240303")
    compileOnly(project(":hidden-api"))
    // Gemini Live WebSocket (Retell/LiveKit until build 55; see deprecated/retell).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // The embedded ADB client (adb/LocalAdb): pairs with this phone's own Wireless debugging and starts the shell
    // helper, replacing Shizuku (build 92). JitPack only; dual GPL-3.0/Apache-2.0, taken under Apache-2.0.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    // The ADB identity's self-signed certificate (adb/AdbIdentity). libadb has bcprov at runtime scope only.
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81")
    // Required for pairing: libadb calls Conscrypt.exportKeyingMaterial, which is hidden in the platform's copy
    // (NoSuchMethodException without this, per JemRec's notes). 2.7.0 for 16 KB pages.
    implementation("org.conscrypt:conscrypt-android:2.7.0")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}

