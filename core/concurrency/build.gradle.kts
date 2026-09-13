plugins {
    id("mihon.library")
}

android {
    namespace = "mihon.core.concurrency"
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    api(kotlinx.coroutines.core)
}
