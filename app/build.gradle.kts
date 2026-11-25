android {
    namespace = "com.example.msgguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.msgguard"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimized.txt"), "proguard-rules.txt")
        }
        debug {
            applicationLabel = "MsgGuard_Debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        // No extra features needed for basic ML inference
    }
}