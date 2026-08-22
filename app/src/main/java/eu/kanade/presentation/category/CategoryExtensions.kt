package eu.kanade.presentation.category

import android.content.Context
import androidx.compose.runtime.Composable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

val Category.visualName: String
    @Composable
    get() = when {
        isSystemCategory -> stringResource(MR.strings.label_default)
        else -> name
    }

fun Category.visualName(context: Context): String =
    when {
        isSystemCategory -> context.stringResource(MR.strings.label_default)
        else -> name
    }

// KMK -->
/**
 * Returns the categories ordered for hierarchical display: every root category
 * (sorted by [Category.order]) followed immediately by its subcategories (also
 * sorted by order). Subcategories whose root no longer exists are appended at
 * the end so they remain selectable.
 */
fun List<Category>.sortedByHierarchy(): List<Category> {
    val roots = filter { it.parentId == 0L }.sortedBy { it.order }
    val subcategories = filter { it.parentId != 0L }
    return buildList {
        val attached = mutableSetOf<Long>()
        roots.forEach { root ->
            add(root)
            subcategories.filter { it.parentId == root.id }
                .sortedBy { it.order }
                .forEach {
                    add(it)
                    attached += it.id
                }
        }
        subcategories.filterNot { it.id in attached }
            .sortedBy { it.order }
            .forEach(::add)
    }
}

/**
 * Label for a category shown in flat selection lists, prefixing subcategories
 * with an indent marker so their nesting under the preceding root category is
 * visible in plain-text rows.
 */
val Category.hierarchicalVisualName: String
    @Composable
    get() = if (parentId == 0L) visualName else "└ $visualName"
// KMK <--
