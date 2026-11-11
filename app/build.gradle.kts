plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kavachcircle.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kavachcircle.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0-hackrush"
    }

    signingConfigs {
        // Demo signing key committed to the repo so every cloud build can update the last one.
        // Replace with a private key before any public release.
        getByName("debug") {
            storeFile = file("kavach-demo.keystore")
            storePassword = "kavachdemo"
            keyAlias = "kavach"
            keyPassword = "kavachdemo"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
}
