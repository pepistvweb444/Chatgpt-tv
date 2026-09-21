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
        versionCode = 5
        versionName = "0.5-compat-test"
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
