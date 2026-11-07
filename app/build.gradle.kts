plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.miles.localllm"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.miles.localllm"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // NDK 配置在这里
        ndk {
            // 只保留 arm64-v8a（现代 Android 设备基本都是这个架构）
            // 如果还要兼容老设备，加 "armeabi-v7a"，但会增大包体积
            abiFilters += listOf("arm64-v8a")

            // 可选：指定使用的 NDK 版本（如果项目根目录有指定可省略）
            // ndkVersion = "25.2.9519653"
        }

        // 如果要用 CMake 编译 JNI 代码（推荐）
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"  // 使用共享 C++ 库，减小体积
            }
        }
    }

    // CMake 路径配置（与上面的 externalNativeBuild 对应）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    // SO 文件打包配置（关键！避免运行时找不到库）
    packaging {
        jniLibs {
            useLegacyPackaging = true  // 不压缩 so，直接加载，避免 UnsatisfiedLinkError
            excludes += "META-INF/**"  // 可选：排除重复文件
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
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
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.coil.compose)
    // 双向流式 TTS：直接走 WebSocket 协议（okhttp），不依赖火山 SDK
    implementation("com.squareup.okhttp3:okhttp:4.9.1")
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}