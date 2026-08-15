import mihon.buildlogic.AndroidConfig
import mihon.buildlogic.configureTest
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.kotlin.multiplatform.library")
    id("mihon.code.lint")
    kotlin("multiplatform")
    alias(libs.plugins.moko)
}

kotlin {
    android {
        compileSdk { version = release(AndroidConfig.COMPILE_SDK) }
        namespace = "tachiyomi.i18n.sy"
        androidResources {
            enable = true
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.moko.core)
            }
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(AndroidConfig.JvmTarget)
    }
}

multiplatformResources {
    resourcesClassName.set("SYMR")
    resourcesPackage.set("tachiyomi.i18n.sy")
}

configureTest()
