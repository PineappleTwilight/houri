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

val ext = project.extensions.extra

val i18nNamespace: String = ext["namespace"] as String
val i18nResourcesPackage: String = ext["resourcesPackage"] as String
val i18nResourcesClassName: String? = ext["resourcesClassName"] as? String

kotlin {
    android {
        compileSdk { version = release(AndroidConfig.COMPILE_SDK) }

        namespace = i18nNamespace

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
    resourcesPackage.set(i18nResourcesPackage)
    if (i18nResourcesClassName != null) {
        resourcesClassName.set(i18nResourcesClassName)
    }
}

configureTest()
