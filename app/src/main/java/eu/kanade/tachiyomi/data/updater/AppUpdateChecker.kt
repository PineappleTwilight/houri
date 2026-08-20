package eu.kanade.tachiyomi.data.updater

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.isFossBuildType
import eu.kanade.tachiyomi.util.system.isPreviewBuildType
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.release.interactor.GetApplicationRelease
import uy.kohesive.injekt.injectLazy

@Inject
@SingleIn(AppScope::class)
class AppUpdateChecker(
    private val peekIntoPreview: Boolean = false,
) {

    private val getApplicationRelease: GetApplicationRelease by injectLazy()

    suspend fun checkForUpdate(
        context: Context,
        forceCheck: Boolean = false,
    ): GetApplicationRelease.Result {
        return withIOContext {
            getApplicationRelease.await(
                GetApplicationRelease.Arguments(
                    isFoss = isFossBuildType,
                    isPreview = isPreviewBuildType || peekIntoPreview,
                    commitCount = BuildConfig.COMMIT_COUNT.toInt(),
                    versionName = BuildConfig.VERSION_NAME,
                    repository = getGithubRepo(peekIntoPreview),
                    forceCheck = forceCheck,
                ),
            )
        }
    }
}

val GITHUB_REPO: String by lazy { getGithubRepo() }

fun getGithubRepo(peekIntoPreview: Boolean = false): String =
    if (isPreviewBuildType || peekIntoPreview) {
        "PineappleTwilight/komikku-pineapple"
    } else {
        "PineappleTwilight/komikku-pineapple"
    }

val RELEASE_TAG: String by lazy { getReleaseTag() }

fun getReleaseTag(peekIntoPreview: Boolean = false): String =
    if (isPreviewBuildType || peekIntoPreview) {
        "r${BuildConfig.COMMIT_COUNT}"
    } else {
        "v${BuildConfig.VERSION_NAME}"
    }

val RELEASE_URL = "https://github.com/$GITHUB_REPO/releases/tag/$RELEASE_TAG"
