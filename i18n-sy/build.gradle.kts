plugins {
    id("mihon.i18n")
}

kotlin {
    android {
        namespace = "tachiyomi.i18n.sy"
    }
}

multiplatformResources {
    resourcesPackage.set("tachiyomi.i18n.sy")
    resourcesClassName.set("SYMR")
}
