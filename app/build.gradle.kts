plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ru.privetdns.zapret"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    defaultConfig {
        applicationId = "ru.privetdns.zapret"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1-beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }
    }
    signingConfigs {
        create("beta") {
            val path = System.getenv("SIGNING_KEYSTORE")
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = "privetzapret"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("beta") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { jniLibs.useLegacyPackaging = true }
    lint { abortOnError = true }
}
val buildTunnel by tasks.registering(Exec::class) {
    val ndk = android.ndkDirectory.absolutePath
    commandLine("$ndk/ndk-build", "-j4", "NDK_PROJECT_PATH=${layout.buildDirectory.get()}/ndk", "NDK_LIBS_OUT=${projectDir}/src/main/jniLibs", "APP_BUILD_SCRIPT=${projectDir}/src/main/jni/Android.mk", "NDK_APPLICATION_MK=${projectDir}/src/main/jni/Application.mk")
    inputs.dir("src/main/jni")
    outputs.dir("src/main/jniLibs")
}
tasks.named("preBuild") { dependsOn(buildTunnel) }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
