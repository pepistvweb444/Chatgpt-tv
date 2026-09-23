plugins {
    id("com.android.application")
}

val initKeystorePath = System.getenv("INIT_KEYSTORE_PATH")
val initKeystorePassword = System.getenv("INIT_KEYSTORE_PASSWORD")
val initKeyAlias = System.getenv("INIT_KEY_ALIAS")
val initKeyPassword = System.getenv("INIT_KEY_PASSWORD")

android {
    namespace = "com.init.mediaaitv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.init.mediaaitv"
        minSdk = 26
        targetSdk = 36
        versionCode = 15
        versionName = "1.5-firetv-accessibility-overlay"
    }

    signingConfigs {
        if (
            !initKeystorePath.isNullOrBlank() &&
            !initKeystorePassword.isNullOrBlank() &&
            !initKeyAlias.isNullOrBlank() &&
            !initKeyPassword.isNullOrBlank()
        ) {
            create("initRelease") {
                storeFile = file(initKeystorePath)
                storePassword = initKeystorePassword
                keyAlias = initKeyAlias
                keyPassword = initKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }

        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            if (signingConfigs.names.contains("initRelease")) {
                signingConfig = signingConfigs.getByName("initRelease")
            }
        }
    }
}
