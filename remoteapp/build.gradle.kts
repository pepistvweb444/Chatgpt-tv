plugins {
    id("com.android.application")
}

android {
    namespace = "com.init.mediaaitv.remoteapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.init.mediaaitv.remoteapp"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }
}
