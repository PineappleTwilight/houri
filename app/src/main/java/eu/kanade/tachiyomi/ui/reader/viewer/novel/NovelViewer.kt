package eu.kanade.tachiyomi.ui.reader.viewer.novel

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.model.ViewerChapters
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.reader.viewer.Viewer
import eu.kanade.tachiyomi.ui.reader.viewer.ViewerProvider
import exh.source.isLightNovel
import mihon.app.di.globalAppGraph

// KMK -->
/**
 * Skeleton novel viewer behind [exh.source.ExhPreferences.isLightNovelEnabled].
 *
 * Renders the chapter as a placeholder until real text-page loading lands
 * (NovelSource/TextPageSource + Epub import via core:archive). Progress and
 * chapter tracking still work: every [moveToPage] reports to the activity.
 */
class NovelViewer(private val activity: ReaderActivity) : Viewer {
    override val isVertical: Boolean = true

    private var chapters: ViewerChapters? = null
    private var title by mutableStateOf("")
    private var body by mutableStateOf("")

    private val view = ComposeView(activity).apply {
        setContent {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            ) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }

    override fun getView(): View = view

    override fun setChapters(chapters: ViewerChapters) {
        this.chapters = chapters
        val pages = chapters.currChapter.pages
        val page = pages?.getOrNull(chapters.currChapter.requestedPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0)))
        if (page != null) moveToPage(page)
    }

    override fun moveToPage(page: ReaderPage) {
        val chapterName = runCatching { page.chapter.chapter.name }.getOrNull().orEmpty()
        title = chapterName.ifBlank { "Chapter ${page.index + 1}" }
        body = "Light novel text rendering is not implemented yet (skeleton viewer). " +
            "Page ${page.index + 1} of \"${title}\"."
        activity.onPageSelected(page)
    }

    override fun destroy() {
        chapters = null
    }

    override fun handleKeyEvent(event: KeyEvent): Boolean = false

    override fun handleGenericMotionEvent(event: MotionEvent): Boolean = false
}

object NovelViewerProvider : ViewerProvider {
    override val order: Int = -10

    override fun create(mode: ReadingMode, activity: ReaderActivity, seedColor: Int?): Viewer? {
        if (!globalAppGraph.exhPreferences.isLightNovelEnabled().get()) return null
        val manga = runCatching { activity.viewModel.manga }.getOrNull() ?: return null
        if (!manga.isLightNovel()) return null
        return NovelViewer(activity)
    }
}
// KMK <--
