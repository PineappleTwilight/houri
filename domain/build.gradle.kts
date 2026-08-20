plugins {
    id("mihon.library")
    kotlin("plugin.serialization")
    alias(libs.plugins.metro)
}

android {
    namespace = "tachiyomi.domain"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

dependencies {
    implementation(projects.sourceApi)
    implementation(projects.core.common)

    implementation(platform(kotlinx.coroutines.bom))
    implementation(platform(compose.bom))
    implementation(kotlinx.bundles.coroutines)
    implementation(kotlinx.bundles.serialization)

    implementation(libs.unifile)

    api(libs.sqldelight.android.paging)

    compileOnly(compose.runtime.annotation)

    testImplementation(libs.bundles.test)
    testImplementation(kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    implementation(libs.metro.runtime)
    implementation(libs.injekt)
}
