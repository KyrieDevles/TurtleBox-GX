plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kyriedevles.turtleboxgx"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kyriedevles.turtleboxgx"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // One fixed key, so every build installs over the previous one.
    // It is committed to this public repo on purpose: this is a sideloaded hobby app, not a store release.
    signingConfigs {
        create("gx") {
            storeFile = file("gx-debug.p12")
            storePassword = "android"
            keyAlias = "gx"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("gx")
        }
        release {
            signingConfig = signingConfigs.getByName("gx")
            isMinifyEnabled = false
        }
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
    implementation("androidx.documentfile:documentfile:1.0.1")
}
