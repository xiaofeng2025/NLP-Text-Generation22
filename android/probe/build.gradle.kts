plugins {
    id("com.android.application")
}

android {
    namespace = "com.familycall.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.familycall.probe"
        // PLAN.md section 6: provisional, revisit once phase 0 reports the box's real Android version.
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        ndk {
            // The UVC library only ships ARM binaries; Xiaomi boxes are ARM.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    lint {
        // Raised by notification helpers inside the UVC library; the probe posts no notifications.
        disable += "NotificationPermission"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Low-level UVC module of AndroidUSBCamera (USBMonitor + UVCCamera). 3.2.7 is the last
    // release JitPack built successfully that still publishes this module on its own.
    implementation("com.github.jiangdongguo.AndroidUSBCamera:libuvc:3.2.7")
}
