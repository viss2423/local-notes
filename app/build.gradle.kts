plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "dev.localnotes"
    compileSdk = 35
    ndkVersion = "27.2.12479018" // Needed so native libraries get their debug symbols stripped.
    defaultConfig {
        applicationId = "dev.localnotes"
        minSdk = 31
        targetSdk = 35
        versionCode = 10
        versionName = "0.10.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes {
        release {
            // Shrunk and optimized; signed with the same key as earlier builds so it installs over them.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = false } }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // lintVital crashes inside lint on the Material3 alpha; full lint runs separately (lintDebug).
    lint {
        checkReleaseBuilds = false
        // Compose 1.9's detector crashes with this AGP/Kotlin analysis API combination.
        disable += "RememberInComposition"
        disable += "FrequentlyChangingValue"
        disable += "NullSafeMutableLiveData"
        disable += "AutoboxingStateCreation"
    }
}
dependencies {
    implementation(project(":language"))
    // sherpa-onnx speech engine (arm64), fetched by tools/bootstrap.py with a SHA-256 check.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui:1.9.0")
    implementation("androidx.compose.foundation:foundation:1.9.0")
    // Material 3 Expressive components (shape morphing, loading indicator); 1.5 alpha builds with compileSdk 35.
    implementation("androidx.compose.material3:material3:1.5.0-alpha10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.9.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.9.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.9.0")
}
