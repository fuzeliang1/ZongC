plugins {
    id("com.android.application")
}

android {
    namespace = "com.zongc.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zongc.phone"
        minSdk = 26          // 安卓 8.0
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 不使用任何第三方库；junit 只用于在电脑上跑核心算法的单元测试
dependencies {
    testImplementation("junit:junit:4.13.2")
}
