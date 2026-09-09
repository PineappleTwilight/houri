import mihon.buildlogic.AndroidConfig
import mihon.buildlogic.configureTest
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.kotlin.multiplatform.library")
    id("mihon.code.lint")
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    android {
        compileSdk { version = release(AndroidConfig.COMPILE_SDK) }
        namespace = "eu.kanade.tachiyomi.source"

        // KMK -->
        // Publish consumer keep rules so R8 sees them when building the app module.
        // Under AGP 9.x + com.android.kotlin.multiplatform.library, the legacy
        // `android { defaultConfig { consumerProguardFiles(...) } }` DSL is gone;
        // consumer rules are SILENTLY DROPPED unless explicitly declared via
        // `optimization { consumerKeepRules { ... } }`. Without this, R8 strips
        // extension-facing synthetic methods (e.g. `JsoupExtensionsKt.asJsoup$default`,
        // `SManga$Companion.create`) → NoSuchMethodError at runtime when
        // classloader-loaded extensions call them.
        optimization {
            consumerKeepRules.apply {
                publish = true
                file("consumer-proguard.pro")
            }
        }
        // KMK <--
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(kotlinx.serialization.json)
                api(libs.injekt)
                api(libs.rxjava)
                api(libs.jsoup)

                // SY -->
                api(projects.i18n)
                api(projects.i18nSy)
                api(kotlinx.reflect)
                // SY <--

                implementation(project.dependencies.platform(compose.bom))
                implementation(compose.runtime)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(projects.core.common)
                api(libs.preferencektx)

                // Workaround for https://youtrack.jetbrains.com/issue/KT-57605
                implementation(kotlinx.coroutines.android)
                implementation(project.dependencies.platform(kotlinx.coroutines.bom))
            }
        }
        // Fix: connect unused commonTest to androidUnitTest to silence
        // "commonTest was configured but not added to any Kotlin compilation" warning
        // Use maybeCreate semantics — androidUnitTest may not exist yet when using
        // `com.android.kotlin.multiplatform.library` (name is androidHostTest in some
        // KMP versions). Create it if absent.
        val commonTest by getting
        // The Android KMP plugin creates `androidUnitTest` lazily; using the
        // `androidUnitTest {}` accessor creates it if missing (unlike `by getting`).
        androidUnitTest {
            dependsOn(commonTest)
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
