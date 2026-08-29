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
    implementation(libs.mlkit.text)
    implementation(libs.mlkit.text.chinese)
    implementation(libs.mlkit.text.japanese)
    implementation(libs.mlkit.text.korean)
    implementation(libs.coroutines.play.services)
    implementation(libs.okio)
}
