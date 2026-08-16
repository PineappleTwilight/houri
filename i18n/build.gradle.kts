plugins {
    id("mihon.i18n")
}

kotlin {
    android {
        namespace = "tachiyomi.i18n"
    }
}

multiplatformResources {
    resourcesPackage.set("tachiyomi.i18n")
}
