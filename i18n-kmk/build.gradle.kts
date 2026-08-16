plugins {
    id("mihon.i18n")
}

kotlin {
    android {
        namespace = "tachiyomi.i18n.kmk"
    }
}

multiplatformResources {
    resourcesPackage.set("tachiyomi.i18n.kmk")
    resourcesClassName.set("KMR")
}
