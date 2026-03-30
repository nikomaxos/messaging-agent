plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.messagingagent.guardian"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.messagingagent.guardian"
        minSdk = 26
        targetSdk = 34
        versionCode = 113
        versionName = "1.1.6"
        buildConfigField("String", "API_BASE_URL", "\"http://rcs.nikomaxos.duckdns.org\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
