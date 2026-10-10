plugins {
    id("com.android.application")
}

fun prop(name: String): String? = (findProperty(name) as String?)?.takeIf { it.isNotEmpty() }

android {
    namespace = "io.github.diontools.mqr"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.diontools.mqr"
        minSdk = 26
        targetSdk = 36
        versionCode = prop("versionCode")?.toInt() ?: 1
        versionName = prop("versionName") ?: "0.0.0-dev"
    }

    signingConfigs {
        // CI から環境変数で渡す。未設定ならデバッグ鍵で署名する
        val storeFile = System.getenv("SIGNING_STORE_FILE")
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += "META-INF/**"
        }
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
}
