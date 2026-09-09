import mihon.buildlogic.AndroidConfig
import mihon.buildlogic.configureTest
import org.gradle.accessors.dm.LibrariesForLibs
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.kotlin.multiplatform.library")
    id("mihon.code.lint")
    kotlin("multiplatform")
    id("dev.icerock.mobile.multiplatform-resources")
}

val libs = the<LibrariesForLibs>()

kotlin {
    android {
        compileSdk { version = release(AndroidConfig.COMPILE_SDK) }

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
        // Fix: Kotlin 2.2 warns "commonTest was configured but not added to any compilation"
        // when using Android KMP + applyDefaultHierarchyTemplate without explicit
        // androidUnitTest -> commonTest linkage. Explicitly connect the hierarchy.
        commonTest {
            dependencies {
            }
        }
        androidUnitTest {
            dependsOn(commonTest.get())
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

configureTest()
