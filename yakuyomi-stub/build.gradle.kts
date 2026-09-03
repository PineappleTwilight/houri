plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
    alias(libs.plugins.metro)
}

android {
    namespace = "exh.yakuyomi.stub"
}

dependencies {
    api(projects.core.common)
    implementation(libs.metro.runtime)
    implementation(projects.core.metro)
    api(kotlinx.coroutines.core)
    api(kotlinx.serialization.json)
    api(libs.preferencektx)
    api(libs.okhttp.core)
}
