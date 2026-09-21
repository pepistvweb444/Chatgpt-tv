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
        versionCode = 10
        versionName = "1.0-dual-continuous-test"
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
