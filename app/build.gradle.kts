plugins {
    id("com.android.application")
}

android {
    namespace = "com.edge20pro.camerakeyprobe"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.edge20pro.camerakeyprobe"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Wpedantic")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}
