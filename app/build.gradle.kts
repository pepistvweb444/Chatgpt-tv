plugins {
    id("com.android.application")
}

android {
    namespace = "com.init.mediaaitv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.init.mediaaitv"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.2-multispeaker-final-test"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
