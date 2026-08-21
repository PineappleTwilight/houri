package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.backupCategoryMapper
import mihon.app.di.globalAppGraph
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category

class CategoriesBackupCreator(
    private val getCategories: GetCategories = globalAppGraph.getCategories,
) {

    suspend operator fun invoke(): List<BackupCategory> {
        return getCategories.await()
            .filterNot(Category::isSystemCategory)
            .map(backupCategoryMapper)
    }
}
