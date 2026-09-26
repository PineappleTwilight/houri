/**
 * Whether to compile the ggml Vulkan backend into `libllama_jni.so`.
 *
 * Defaults to **on**: MTL ships full AI, not a CPU-only subset, so the GPU path is part of
 * the normal build. It needs Vulkan-Hpp + SPIRV-Headers (which no Android SDK ships) and
 * compiles against API 29+, so the build host must provide them — run
 * `llamatik-native/wsl-setup.sh` inside WSL once, or see
 * `llamatik-native/BUILD.md`.
 *
 * Set `MTL_GPU_OFFLOAD=false` (or the `mtl.gpuOffload` Gradle property) to fall back to a
 * CPU-only native lib on a machine that has no Vulkan toolchain.
 */
val gpuOffload: Boolean = (
    providers.gradleProperty("mtl.gpuOffload").orNull
        ?: providers.environmentVariable("MTL_GPU_OFFLOAD").orNull
    )
    ?.toBooleanStrictOrNull()
    ?: true

/**
 * Optional Vulkan SDK root, e.g. `/opt/VulkanSDK/1.4.313`. Only needed with [gpuOffload]:
 * the NDK carries the Vulkan loader stub and headers, but not Vulkan-Hpp's
 * `vulkan/vulkan.hpp`, nor SPIRV-Headers. Falls back to the `VULKAN_SDK` environment
 * variable, which llama.cpp's CMake honours. `wsl-setup.sh` stages a header-only
 * equivalent at [wslVulkanHeaders], which CMake auto-detects.
 */
val vulkanSdkDir: String? = (
    providers.gradleProperty("mtl.vulkanSdkDir").orNull
        ?: providers.environmentVariable("VULKAN_SDK").orNull
    )
    ?.takeIf { it.isNotBlank() }

/**
 * Semicolon-separated include dirs, for when [vulkanSdkDir] is not enough — e.g. a
 * Vulkan-Headers + Vulkan-Hpp pair (which must be the *same* version) plus
 * SPIRV-Headers. The directory must contain only the Vulkan/SPIR-V headers: adding a
 * host `/usr/include` shadows the NDK's libc headers and breaks the build.
 */
val vulkanIncludeDirs: String? = providers.gradleProperty("mtl.vulkanIncludeDirs").orNull
    ?.takeIf { it.isNotBlank() }

/**
 * SPIRV-Headers CMake config dir. The NDK toolchain sets
 * `CMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY`, so a distro-installed SPIRV-Headers is not
 * discoverable; CMake generates a shim config and this only overrides it.
 */
val spirvHeadersDir: String? = providers.gradleProperty("mtl.spirvHeadersDir").orNull
    ?.takeIf { it.isNotBlank() }

/**
 * Which glslc to use *inside WSL*: `auto` (default), `wsl`, or `native`.
 *
 * Only consulted on the WSL build path. A Windows host never configures CMake itself any
 * more (see [hostIsWindows]), so the old cross-boundary glslc proxy is not used there.
 */
val vulkanToolchain: String = providers.gradleProperty("mtl.vulkanToolchain").orNull
    ?.takeIf { it.isNotBlank() }
    ?: "auto"

/**
 * NDK to use **inside WSL**. Deliberately not the Windows SDK's NDK: a Linux cmake cannot
 * use `windows-x86_64` toolchain binaries. Install it once with
 * `sudo sdkmanager --install "ndk;28.2.13676358"` from inside WSL, or point this elsewhere.
 */
val wslNdkDir: String = providers.gradleProperty("mtl.wslNdkDir").orNull
    ?.takeIf { it.isNotBlank() }
    ?: "/usr/lib/android-sdk/ndk/28.2.13676358"

/** Where the WSL build stages the headers `wsl-setup.sh` creates. */
val wslVulkanHeaders: String = providers.gradleProperty("mtl.wslVulkanHeaders").orNull
    ?.takeIf { it.isNotBlank() }
    ?: "/opt/vulkan-headers"

/**
 * WSL is the single path for Windows native builds — no MSYS2/Git Bash fallback, matching
 * `external/imagedecoder-houri`. Proxying individual tool invocations across the
 * Windows/WSL boundary is not viable here: ggml-vulkan shells out to glslc ~40 times per
 * ABI, and every one of those would pay a fresh VM round-trip. Worse, the Vulkan *headers*
 * cannot be proxied at all — a Windows `clang++.exe` cannot `#include` anything inside the
 * WSL root filesystem, so a partially-proxied build fails deep into compilation with
 * `'vulkan/vulkan.hpp' file not found`.
 *
 * So on Windows the whole configure+build runs inside WSL (see the `wslNativeBuild` task)
 * and AGP only packages the resulting `libllama_jni.so` from `jniLibs`. The Linux/WSL/CI
 * path is untouched and still uses AGP's own `externalNativeBuild`.
 */
val hostIsWindows: Boolean = System.getProperty("os.name").contains("Windows", ignoreCase = true)

val nativeAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

/**
 * `C:/Windows/Sysnative` matters: a 32-bit Gradle daemon has `System32` redirected to
 * `SysWOW64`, where `wsl.exe` does not exist, so a plain PATH lookup reports a false miss.
 */
val wslExe: File? = listOf(
    File("C:/Windows/System32/wsl.exe"),
    File("C:/Windows/Sysnative/wsl.exe"),
).firstOrNull { it.isFile }

if (hostIsWindows && wslExe == null) {
    throw GradleException(
        "llamatik-native: WSL is required to build the native LLM runtime on Windows, and " +
            "wsl.exe was not found in C:/Windows/System32 or C:/Windows/Sysnative.\n" +
            "WSL is the single supported path — there is deliberately no MSYS2/Git Bash " +
            "fallback, because the Vulkan headers live inside the WSL root filesystem and " +
            "cannot be read by a Windows compiler.\n" +
            "  Install/enable WSL:  wsl --install -d Debian\n" +
            "  Then set it up once:  wsl -e bash <repo>/llamatik-native/wsl-setup.sh\n" +
            "  Or build CPU-only without any of this:  -Pmtl.gpuOffload=false\n" +
            "See llamatik-native/BUILD.md."
    )
}

plugins {
    id("mihon.library")
}

/**
 * Where the WSL build drops each `libllama_jni.so`.
 *
 * Deliberately the conventional `src/main/jniLibs/<abi>/` rather than a `build/` dir wired in
 * through `android.sourceSets`. AGP picks up `src/main/jniLibs` with no DSL at all, and the
 * `sourceSets` accessors throw
 * "DefaultAndroidLibrarySourceSet_Decorated cannot be cast to AndroidLibrarySourceSet"
 * (a classloader/decorator mismatch from a stale AGP jar in the Gradle cache) — there is no
 * reason to depend on that code path just to name a directory. It is gitignored.
 */
val wslJniLibsDir = file("src/main/jniLibs")

android {
    namespace = "com.llamatik.library.platform"
    ndkVersion = "28.2.13676358"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        // All four ABIs the app ships (see `app/build.gradle.kts` splits.abi.include and
        // external/imagedecoder-houri, which is also unfiltered). With per-ABI splits, an
        // ABI missing here yields a split APK with no LLM runtime at all. This also filters
        // the jniLibs consumed on the Windows/WSL path.
        ndk {
            abiFilters += nativeAbis
        }
        buildConfigField("boolean", "GPU_OFFLOAD_COMPILED_IN", gpuOffload.toString())
        consumerProguardFiles("consumer-rules.pro")

        // Only the Linux-side path configures CMake through AGP.
        if (!hostIsWindows) {
            externalNativeBuild {
                cmake {
                    arguments += "-DHOURI_GPU_OFFLOAD=${if (gpuOffload) "ON" else "OFF"}"
                    arguments += "-DANDROID_STL=c++_static"
                    cppFlags += "-std=c++17"
                    if (gpuOffload) {
                        // ggml-vulkan needs a Vulkan 1.1 core symbol that the NDK's
                        // libvulkan.so stub only exports from API 29. The app itself still
                        // supports API 26 and falls back to the CPU there. CMake aborts if
                        // AGP overrides this back to the module's minSdk.
                        arguments += "-DANDROID_PLATFORM=android-29"
                        arguments += "-DMTL_VULKAN_TOOLCHAIN=$vulkanToolchain"
                        vulkanSdkDir?.let { arguments += "-DMTL_VULKAN_SDK_DIR=$it" }
                        vulkanIncludeDirs?.let { arguments += "-DMTL_VULKAN_INCLUDE_DIRS=$it" }
                        spirvHeadersDir?.let { arguments += "-DSPIRV-Headers_DIR=$it" }
                    }
                }
            }
        }
    }

    if (!hostIsWindows) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }
}

/**
 * Runs the entire native build inside WSL, then leaves `src/main/jniLibs/<abi>/libllama_jni.so`
 * for AGP to package. Only registered on a Windows host — on Linux/WSL/CI AGP's own
 * `externalNativeBuild` is used instead and this task does not exist.
 *
 * Incrementality is delegated to ninja: this task never reports up-to-date, so it re-runs,
 * but a no-change rebuild is just ninja's own up-to-date check (seconds) rather than the
 * ~20 minutes a from-scratch four-ABI Vulkan build costs. Gradle cannot track ninja's
 * in-place object updates, so trusting its own snapshot here would skip real work.
 */
if (hostIsWindows) {
    val wslNativeBuild = tasks.register("wslNativeBuild") {
        group = "build"
        description = "Builds libllama_jni.so for every ABI inside WSL (Windows host path)."

        val exe = wslExe!!
        val abis = nativeAbis
        val gpu = gpuOffload
        val ndk = wslNdkDir
        val headers = wslVulkanHeaders
        val toolchain = vulkanToolchain
        val modulePath = projectDir.absolutePath

        outputs.dir(wslJniLibsDir)
        outputs.upToDateWhen { false }

        doLast {
            // C:/x/y -> /mnt/c/x/y, so a build dir on a Windows drive stays reachable in WSL.
            fun toWsl(path: String): String {
                val f = path.replace('\\', '/')
                val m = Regex("^([A-Za-z]):/(.*)$").find(f)
                return if (m == null) f else "/mnt/${m.groupValues[1].lowercase()}/${m.groupValues[2]}"
            }

            // The shell lives in its own file rather than inline here: Kotlin raw strings
            // cannot escape a '$', so an inlined script would need ${'$'} before every one and
            // any miss fails at script-compile time with "Unresolved reference".
            val scriptWsl = toWsl(file("src/main/cpp/build-wsl.sh").absolutePath)
            val moduleWsl = toWsl(modulePath)
            val outWsl = toWsl(wslJniLibsDir.absolutePath)

            // Only override the include dir when it is not CMake's auto-detected default.
            val includeArg = if (gpu && headers != "/opt/vulkan-headers") headers else ""

            logger.lifecycle(
                "llamatik-native: building ${abis.joinToString(", ")} inside WSL via ${exe.path}"
            )
            val proc = ProcessBuilder(
                exe.path,
                "bash",
                scriptWsl,
                moduleWsl,
                ndk,
                if (gpu) "ON" else "OFF",
                toolchain,
                includeArg,
                outWsl,
            )
                .redirectErrorStream(true)
                .start()
            proc.inputStream.bufferedReader().forEachLine { logger.lifecycle(it) }
            val rc = proc.waitFor()
            check(rc == 0) { "llamatik-native: WSL native build failed (exit $rc). See wsl-setup.sh." }

            // Fail loudly rather than shipping an APK whose split is missing the runtime.
            val missing = abis.filter {
                !File(wslJniLibsDir, "$it/libllama_jni.so").isFile
            }
            check(missing.isEmpty()) {
                "llamatik-native: WSL build did not produce libllama_jni.so for: " +
                    missing.joinToString()
            }
        }
    }

    // Everything that consumes the merged native libraries must wait for the WSL build.
    tasks.matching {
        val n = it.name
        n.contains("JniLibFolders") || n.contains("mergeNativeLibs") || n.contains("Strip")
    }.configureEach {
        dependsOn(wslNativeBuild)
    }
}
