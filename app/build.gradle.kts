plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.fioiu8.dshpocket"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.fioiu8.dshpocket"
        minSdk = 24
        // 关键决策：targetSdk 28 —— 侧载分发，豁免 Android 10+ 的 W^X 限制，
        // 允许从 filesDir 直接 execve 随包分发的 bionic 二进制（Termux 同款策略）。
        // 依据见 docs/architecture-review.md 的 D7 复议；代价是无法上架 Play。
        targetSdk = 28
        versionCode = providers.gradleProperty("appVersionCode").orNull?.toIntOrNull() ?: 1
        versionName = providers.gradleProperty("appVersionName").orNull ?: "1.0"

        val runtimeArch = providers.gradleProperty("runtimeArch").orNull ?: "x86_64"
        val bundledAbi = when (runtimeArch) {
            "x86_64" -> "x86_64"
            "aarch64" -> "arm64-v8a"
            else -> error("Unsupported runtimeArch: $runtimeArch")
        }
        buildConfigField("String", "BUNDLED_ABI", "\"$bundledAbi\"")
        buildConfigField("String", "RUNTIME_ARCH", "\"$runtimeArch\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // The build sandbox cannot write ~/.android, so the debug keystore lives in the
        // project instead of being auto-generated. Credentials are AGP's public defaults.
        // Create it with: keytool -genkeypair -keystore .keystore/debug.keystore \
        //   -alias androiddebugkey -storepass android -keypass android -dname "CN=Android Debug,O=Android,C=US"
        create("debugLocal") {
            storeFile = rootProject.file(".keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("releaseCi") {
            val keystorePath = providers.gradleProperty("releaseStoreFile").orNull
            if (keystorePath != null) {
                storeFile = rootProject.file(keystorePath)
                storePassword = providers.gradleProperty("releaseStorePassword").orNull
                keyAlias = providers.gradleProperty("releaseKeyAlias").orNull
                keyPassword = providers.gradleProperty("releaseKeyPassword").orNull
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugLocal")
        }
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            if (providers.gradleProperty("releaseStoreFile").isPresent) {
                signingConfig = signingConfigs.getByName("releaseCi")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // targetSdk 28 is deliberate: the side-loaded APK executes its bundled
        // bionic runtime from app-private storage. Keep this policy explicit while
        // allowing debug and release artifacts to pass lint.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.okhttp)
    // 真 PTY 终端：Termux 的终端模拟与视图（Apache-2.0，JitPack）。
    implementation(libs.termux.terminal.emulator)
    implementation(libs.termux.terminal.view)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
