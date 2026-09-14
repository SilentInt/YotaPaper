plugins {
    id("com.android.application")
}

android {
    namespace = "com.yota.launcher"
    compileSdk = 36
    enableKotlin = true

    defaultConfig {
        applicationId = "com.yota.launcher"
        minSdk = 17
        targetSdk = 36
        versionCode = 6
        versionName = "0.6"
    }

    signingConfigs {
        // Publish builds with the standard Android debug/test key so the
        // release APK is directly installable without a private keystore.
        create("release") {
            storeFile = file(System.getProperty("user.home") + "/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt")
        }
    }
}

dependencies {
    // Yota SDK is a compile-time API stub; actual implementation exists on Yota devices.
    compileOnly(files("../libs/yotadevice_sdk-full-stub.jar"))

    // Xposed API 编译期桩，只在被 Xposed 加载时使用；不打包进 APK。
    compileOnly(files("../libs/XposedBridgeApi-stub.jar"))

    // QR code generation for the WiFi transfer feature.
    implementation(files("../libs/zxing-core-3.5.1.jar"))
    // 新增 Libsu 依赖：用于维护全局长连接的 Root Shell
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
}