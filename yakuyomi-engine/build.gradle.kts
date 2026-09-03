plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
    alias(libs.plugins.metro)
}

android {
    namespace = "exh.yakuyomi"
    ndkVersion = "27.0.12077973"
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
    implementation("li.joye.yakuyomi:engine")
    // On-device Gemini Nano LLM via ML Kit GenAI Prompt API (priority provider when available).
    implementation(libs.mlkit.genai.prompt)
    // On-device LLM NPU/CPU runtime (Qualcomm QNN / MediaTek NeuroPilot / XNNPACK).
    implementation(libs.executorch.android)
}
