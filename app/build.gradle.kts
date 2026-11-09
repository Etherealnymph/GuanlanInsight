plugins {
    id("com.android.application")
}

android {
    namespace = "com.etherealnymph.guanlan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.etherealnymph.guanlan"
        minSdk = 29
        targetSdk = 34
        versionCode = 5
        versionName = "1.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// The app intentionally has no third-party runtime dependencies. Everything is
// built on the Android framework so the WebView shell stays small and predictable.
