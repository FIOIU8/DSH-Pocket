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
        versionCode = 1
        versionName = "1.0"

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
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
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
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
