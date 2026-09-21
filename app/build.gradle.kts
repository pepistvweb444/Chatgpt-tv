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
        versionCode = 7
        versionName = "0.7-live-test"
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
