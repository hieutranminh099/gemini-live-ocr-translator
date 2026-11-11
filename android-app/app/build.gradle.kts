plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Disposable debug-only package for native verification. It cannot replace the installed app.
val verificationAppId = providers.gradleProperty("verificationAppId").orNull?.also {
    require(it.startsWith("com.gemini.ocr.") && it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))) {
        "verificationAppId must be a separate package below com.gemini.ocr"
    }
}
// Keep opt-in instrumentation separate from other developers' installed test packages.
val verificationTestAppId = providers.gradleProperty("verificationTestAppId").orNull?.also {
    require(it.startsWith("com.gemini.ocr.") && it.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")))
}
// Test APK only: let Android deny this disposable app's networking without changing host settings.
// An explicitly supplied false/misspelled value is rejected instead of silently testing online.
val verificationOffline = providers.gradleProperty("verificationOffline").orNull?.also {
    require(it == "true") { "verificationOffline accepts only the exact value true; omit it for normal builds" }
    require(verificationAppId in setOf("com.gemini.ocr.codexbench", "com.gemini.ocr.batchunified186", "com.gemini.ocr.asr187", "com.gemini.ocr.movie189")) {
        "verificationOffline requires a dedicated fixture package"
    }
} != null
android {
    namespace = "com.gemini.ocr"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.gemini.ocr"
        minSdk = 26
        targetSdk = 35
        versionCode = 38
        versionName = "1.8.13"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        if (verificationTestAppId != null) testApplicationId = verificationTestAppId
        manifestPlaceholders["appLabel"] = "Gemini OCR"
        manifestPlaceholders["stabilityPipeAuthority"] = "${verificationTestAppId ?: "${verificationAppId ?: "com.gemini.ocr"}.test"}.stabilitypipe"
        val envGeminiKey = "" // Distributable APKs use the encrypted key entered by their owner.
            .replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "GEMINI_API_KEY", "\"$envGeminiKey\"")
        buildConfigField("boolean", "VERIFICATION_OFFLINE_NETWORK", "false")
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug {
            buildConfigField("boolean", "VERIFICATION_OFFLINE_NETWORK", verificationOffline.toString())
            // Only src/debug consumes this removal marker. Release never includes that manifest.
            manifestPlaceholders["verificationInternetPermission"] = if (verificationOffline)
                "android.permission.INTERNET" else "com.gemini.ocr.codexbench.permission.UNUSED_NETWORK_REMOVAL_MARKER"
            if (verificationAppId != null) {
                applicationIdSuffix = verificationAppId.removePrefix("com.gemini.ocr")
                manifestPlaceholders["appLabel"] = "Gemini OCR • Thử"
            }
            if (file("${rootDir}/debug.keystore").exists()) {
                signingConfig = signingConfigs.getByName("debugConfig")
            }
        }
        release { isMinifyEnabled = false }
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.maxHeapSize = "2g"
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            it.systemProperty("gemini.test.screenshots", layout.buildDirectory.dir("test-screenshots").get().asFile.absolutePath)
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED", "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED", "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED"
            )
        }
    }
}
dependencies {
    // Official pinned static-ORT build avoids collisions with the isolated ORT test runtime.
    // Fetch and verify with: python scripts/setup_android_asr.py
    implementation(files("libs/sherpa-onnx-static-link-onnxruntime-2.0.1-app.aar"))
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Language minimization (ZH -> VI only): keep exclusively ML Kit Chinese Text Recognition (Simplified & Traditional)
    // implementation("com.google.mlkit:text-recognition:16.0.1")
    // implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    // implementation("com.google.mlkit:translate:17.0.3")
    testImplementation("junit:junit:4.13.2")
    // Explicit experimental offline mode for saved video; never a fallback for Live/Gemini.
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // Video processing stays on Android; translation mode is chosen explicitly by the owner.
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-transformer:1.5.1")
    // Expose the muxer already used by Transformer at runtime for timestamp-safe concatenation.
    implementation("androidx.media3:media3-muxer:1.5.1")
    implementation("androidx.media3:media3-effect:1.5.1")
    implementation("androidx.media3:media3-extractor:1.5.1")
    // Isolated OCR comparison; production capture keeps ML Kit until measured.
    debugImplementation(project(":ocr-candidate"))
    debugImplementation("com.microsoft.onnxruntime:onnxruntime-extensions-android:0.13.0")
    androidTestImplementation("com.microsoft.onnxruntime:onnxruntime-android:1.21.1")
    // The native colored-pixel parity test accesses the same OpenCV dependency directly.
    androidTestImplementation("com.quickbirdstudios:opencv:4.5.3")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
