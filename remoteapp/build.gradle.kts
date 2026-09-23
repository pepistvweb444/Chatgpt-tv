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
        versionCode = 2
        versionName = "1.1"
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
