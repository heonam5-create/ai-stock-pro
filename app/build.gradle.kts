plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.aistock.pro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aistock.pro"
        minSdk = 26          // Android 8.0 이상
        targetSdk = 35
        // 업데이트할 때마다 versionCode 를 1씩 올리면 기존 데이터가 유지된 채 덮어쓰기 설치됩니다.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = "1.0." + (System.getenv("VERSION_CODE") ?: "1")
        vectorDrawables { useSupportLibrary = true }
    }

    // 서명키는 저장소에 포함하지 않습니다. GitHub Secrets(KEYSTORE_BASE64 등)로 주입된
    // 키가 있으면 그 키로 서명하고, 없으면 디버그 키로 서명합니다(업데이트 설치 시 서명 불일치 가능).
    val ksPath = System.getenv("KEYSTORE_PATH")
    val hasReleaseKey = !ksPath.isNullOrBlank() && file(ksPath).exists()
    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(ksPath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.environment("LIVE_QUOTE_TEST", System.getenv("LIVE_QUOTE_TEST") ?: "0")
            System.getenv("LIVE_QUOTE_OUT")?.let { o -> it.environment("LIVE_QUOTE_OUT", o) }
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // 단위 테스트에서 실제 org.json 사용 (안드로이드 스텁 대체)
}
