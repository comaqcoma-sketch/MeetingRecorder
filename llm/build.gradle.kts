plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// 내장 AI 요약 엔진 (llama.cpp). whisper와 내부 라이브러리가 겹치지 않도록 별도 모듈로 분리
android {
    namespace = "com.mplan.llm"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                cppFlags += "-O3"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
