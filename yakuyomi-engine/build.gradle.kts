plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
    alias(libs.plugins.metro)
}

android {
    namespace = "exh.yakuyomi"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

dependencies {
    api(projects.core.common)
    api(libs.okhttp.core)
    api(kotlinx.coroutines.core)
    api(kotlinx.serialization.json)
    api(libs.preferencektx)
    implementation(libs.metro.runtime)
    implementation(projects.core.metro)
    implementation(projects.core.concurrency)
    implementation("li.joye.yakuyomi:engine")
    // On-device Gemini Nano LLM via ML Kit GenAI Prompt API (priority provider when available).
    implementation(libs.mlkit.genai.prompt)
    // On-device GGUF LLM runtime (llama.cpp via Llamatik).
    implementation(libs.llamatik)
    // Shared ORT runtime for OCR and the NNAPI upscaler backend.
    implementation(libs.onnxruntime.android)
    // KMK --> fake-OkHttp auth tests (401→refresh→retry-once, signup-200-stores-token, 429→friendlyError)
    testImplementation(libs.bundles.test)
    testImplementation(kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    // KMK <--
}
