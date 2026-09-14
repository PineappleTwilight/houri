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
    implementation(projects.core.concurrency)
    implementation("li.joye.yakuyomi:engine")
    // On-device Gemini Nano LLM via ML Kit GenAI Prompt API (priority provider when available).
    implementation(libs.mlkit.genai.prompt)
    // On-device GGUF LLM runtime (llama.cpp via Llamatik).
    implementation(libs.llamatik)
    // KMK --> fake-OkHttp auth tests (401→refresh→retry-once, signup-200-stores-token, 429→friendlyError)
    testImplementation(libs.bundles.test)
    testImplementation(kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    // KMK <--
}
