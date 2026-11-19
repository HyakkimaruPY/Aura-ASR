plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aurareels.ocrlab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aurareels.ocrlab"
        minSdk = 26
        targetSdk = 34
        versionCode = 33
        versionName = "0.7.6"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // Release signing comes from the environment, never from the source tree.
    signingConfigs {
        create("release") {
            val keystore = System.getenv("AURA_KEYSTORE")
            if (keystore != null) {
                storeFile = file(keystore)
                storePassword = System.getenv("AURA_KEYSTORE_PASS")
                keyAlias = System.getenv("AURA_KEY_ALIAS")
                keyPassword = System.getenv("AURA_KEY_PASS")
            }
        }
    }
    buildTypes {
        release {
            // R8: renames and flattens every class/method, removes dead code and debug logs.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("AURA_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // .onnx must stay uncompressed so the bundled detector copies fast and APK size is predictable.
    androidResources { noCompress += listOf("onnx") }
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES")
        // sherpa-onnx ships its own ONNX Runtime. It is renamed to libsherpaort.so at build time
        // (scripts/isolate_sherpa_ort.py) so it never collides with libonnxruntime.so from the OCR AAR.
        // No pickFirst here: a duplicate libonnxruntime.so must fail the build, not the app at runtime.
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.6.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.28.0")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("com.github.k2-fsa.sherpa-onnx:sherpa-onnx-jvm:v1.13.8")
    implementation("org.apache.commons:commons-compress:1.26.2")

    testImplementation("junit:junit:4.13.2")
}

// Provisions the sherpa-onnx ARM64 JNI + its ONNX Runtime (renamed to avoid the AAR clash). Without
// this the APK has no libsherpa-onnx-jni.so ("library not found") or a wrong libonnxruntime.so
// ("cannot locate symbol OrtGetApiBase"). Needs python3 + internet only the first time.
tasks.register<Exec>("prepareSherpaRuntime") {
    val jni = file("src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so")
    val ort = file("src/main/jniLibs/arm64-v8a/libsherpaort.so")
    onlyIf { !(jni.exists() && ort.exists()) }
    commandLine("sh", "-c", "python3 '${rootDir}/scripts/prepare_sherpa_arm64.py' && python3 '${rootDir}/scripts/isolate_sherpa_ort.py'")
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn("prepareSherpaRuntime") }

// Embeds the spoken-language detector (assets/lid) before every build. Needs python3 + internet
// only the first time; skipped when the files already exist.
tasks.register<Exec>("fetchLidModel") {
    val marker = file("src/main/assets/lid/tiny-decoder.int8.onnx")
    onlyIf { !marker.exists() }
    commandLine("python3", "${rootDir}/scripts/fetch_lid_model.py")
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn("fetchLidModel") }
