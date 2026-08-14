package tachiyomi.domain.chapter.interactor

import tachiyomi.domain.chapter.model.Chapter

class NeedsChapterUpdate {

    fun await(dbChapter: Chapter, sourceChapter: Chapter): Boolean {
        return dbChapter.scanlator != sourceChapter.scanlator ||
            dbChapter.name != sourceChapter.name ||
            dbChapter.dateUpload != sourceChapter.dateUpload ||
            dbChapter.chapterNumber != sourceChapter.chapterNumber ||
            dbChapter.sourceOrder != sourceChapter.sourceOrder ||
            dbChapter.memo != sourceChapter.memo
    }
}
