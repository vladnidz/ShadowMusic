plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.shadow.music"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shadow.music"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.6.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE.md,LICENSE-notice.md,INDEX.LIST,DEPENDENCIES}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // NewPipe Extractor v0.26.5: fixes 'The page needs to be reloaded' & TVHTML5/SABR bypass
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    // Pure Kotlin ADB client for direct, instant Wi-Fi push to watch
    implementation("dev.mobile:dadb:1.2.9")
}
