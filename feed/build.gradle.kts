/*
 * Open Launcher Feed（新聞頁外掛）
 *
 * 版本號必須與根專案 gradle/libs.versions.toml 一致：
 *   agp = "9.4.1"、kotlin = "2.4.20"、jdkRelease = "21"
 *   buildToolsVersion "37.0.0"、compileSdk 37、targetSdk 37、minSdk 26
 * 這裡刻意硬寫版本（而非引用根專案的 version catalog），讓 feed/ 能完全獨立建置。
 */

plugins {
    // AGP 9 起內建 Kotlin 支援，不可以（也不需要）再套用 org.jetbrains.kotlin.android。
    id("com.android.application") version "9.4.1"
}

android {
    namespace = "app.openlauncher.feed"
    buildToolsVersion = "37.0.0"

    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "app.openlauncher.feed"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    buildTypes {
        // 這個 APK 的「唯一存在理由」就是它是 debuggable 的：
        // Google app 的 overlay service 只接受系統 app 或 debuggable app 當客戶端，
        // 一般安裝的啟動器兩者都不是。因此 debug 與 release 都必須 debuggable。
        // AGP 會用這裡的值覆寫 manifest 中的 android:debuggable，兩邊都設是為了
        // 讓任何人讀 manifest 就知道這是刻意的。
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            isDebuggable = true
            isMinifyEnabled = false
            // 方便實機階段直接安裝；正式發佈時請換成自己的 keystore，
            // 並依 README 的說明把簽章雜湊填回啟動器的 bridge.xml。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = true
        // 見上方註解：release 版 debuggable 是本專案的設計前提。
        disable += "HardcodedDebugMode"
        // local.properties 是每台機器自己的檔案（已被 git 忽略），不是交付內容。
        disable += "PropertyEscape"
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // 刻意不引入任何第三方相依（AndroidX 亦不需要），只用 Kotlin 標準函式庫。
    testImplementation("junit:junit:4.13.2")
}
