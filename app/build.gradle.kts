plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.dji.recreate2"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dji.recreate2"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }
    
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        jniLibs.pickFirsts.add("lib/arm64-v8a/libc++_shared.so")
        jniLibs.pickFirsts.add("lib/armeabi-v7a/libc++_shared.so")
        
        jniLibs.keepDebugSymbols.add("*/*/libconstants.so")
        jniLibs.keepDebugSymbols.add("*/*/libdji_innertools.so")
        jniLibs.keepDebugSymbols.add("*/*/libdjibase.so")
        jniLibs.keepDebugSymbols.add("*/*/libDJICSDKCommon.so")
        jniLibs.keepDebugSymbols.add("*/*/libDJIFlySafeCore-CSDK.so")
        jniLibs.keepDebugSymbols.add("*/*/libdjifs_jni-CSDK.so")
        jniLibs.keepDebugSymbols.add("*/*/libDJIRegister.so")
        jniLibs.keepDebugSymbols.add("*/*/libdjisdk_jni.so")
        jniLibs.keepDebugSymbols.add("*/*/libDJIUpgradeCore.so")
        jniLibs.keepDebugSymbols.add("*/*/libDJIUpgradeJNI.so")
        jniLibs.keepDebugSymbols.add("*/*/libDJIWaypointV2Core-CSDK.so")
        jniLibs.keepDebugSymbols.add("*/*/libdjiwpv2-CSDK.so")
        jniLibs.keepDebugSymbols.add("*/*/libFlightRecordEngine.so")
        jniLibs.keepDebugSymbols.add("*/*/libvideo-framing.so")
        jniLibs.keepDebugSymbols.add("*/*/libwaes.so")
        jniLibs.keepDebugSymbols.add("*/*/libagora-rtsa-sdk.so")
        jniLibs.keepDebugSymbols.add("*/*/libc++.so")
        jniLibs.keepDebugSymbols.add("*/*/libc++_shared.so")
        jniLibs.keepDebugSymbols.add("*/*/libmrtc_28181.so")
        jniLibs.keepDebugSymbols.add("*/*/libmrtc_agora.so")
        jniLibs.keepDebugSymbols.add("*/*/libmrtc_core.so")
        jniLibs.keepDebugSymbols.add("*/*/libmrtc_core_jni.so")
        jniLibs.keepDebugSymbols.add("*/*/libmrtc_data.so")
        jniLibs.keepDebugSymbols.add("*/*/libmrtc_log.so")
        jniLibs.keepDebugSymbols.add("*/*/libmrtc_onvif.so")
        jniLibs.keepDebugSymbols.add("*/*/libmrtc_rtmp.so")
        jniLibs.keepDebugSymbols.add("*/*/libmrtc_rtsp.so")
        jniLibs.keepDebugSymbols.add("*/*/libSdkyclx_clx.so")
        jniLibs.keepDebugSymbols.add("*/*/libdataclx.so")
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    
    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        unitTests {
            // android.util.Log is a stub in the host JVM and throws "not mocked" by default.
            // GimbalLimits and CameraProjection both log, so every test touching them would
            // fail on the logging, not on the logic.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    // DJI MSDK v5 Dependencies (Core ONLY, no UXSDK)
    implementation(libs.dji.sdk.v5.aircraft)
    compileOnly(libs.dji.sdk.v5.aircraft.provided)
    implementation(libs.dji.sdk.v5.networkImp)
    
    // Tactical Map Integration
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    
    // MQTT Server Integration (Edge Telemetry)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    
    // EXIF
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    
    // HTTP OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Host-side unit tests
    testImplementation(libs.junit)
    // Every DJI type lives in the -provided jar, which is compileOnly above and so does not
    // reach the test source set. Without this the test compiler cannot resolve
    // ComponentIndexType in CameraProjection.refreshVideoSize's signature. compileOnly is
    // deliberate: the tests never LOAD a DJI class, and keeping the stub jar off the runtime
    // classpath stops a stub static initialiser from running on the host.
    testCompileOnly(libs.dji.sdk.v5.aircraft.provided)
}

