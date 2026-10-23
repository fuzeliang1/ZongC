// MiniDiDi 手机车（安卓 App）
// 国内下载慢时，可以把下面三处 repositories 里的阿里云镜像取消注释，放在最前面。
pluginManagement {
    repositories {
        // maven { url = uri("https://maven.aliyun.com/repository/google") }
        // maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        // maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // maven { url = uri("https://maven.aliyun.com/repository/google") }
        // maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "MiniDiDiPhone"
include(":app")
