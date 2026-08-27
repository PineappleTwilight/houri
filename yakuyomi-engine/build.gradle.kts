plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
    alias(libs.plugins.metro)
}

android {
    namespace = "exh.yakuyomi"
    // ABI split for native payload is handled at :app level; engine itself is CPU-only stubs
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
    api(projects.domain)
    api(libs.okhttp.core)
    api(kotlinx.coroutines.core)
    api(kotlinx.serialization.json)
    api(libs.preferencektx)
    implementation(libs.metro.runtime)
    implementation(projects.core.metro)
    // Stubbed native deps — real NCNN/ONNX/AOT-GAN provided via app abi split when enabled
    compileOnly(libs.okio)
}
