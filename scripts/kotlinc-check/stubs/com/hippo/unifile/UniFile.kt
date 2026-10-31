// Offline compile stub - never shipped. Real UniFile comes from jitpack and is not always cached.
@file:Suppress("unused", "UNUSED_PARAMETER")
package com.hippo.unifile

import java.io.File
import java.io.InputStream
import java.io.OutputStream

open class UniFile {
    val name: String? = null
    val uri: android.net.Uri = android.net.Uri.EMPTY
    val filePath: String? = null
    val isDirectory: Boolean = false
    val isFile: Boolean = false
    val canRead: Boolean = false
    val canWrite: Boolean = false
    // NB: no `extension` / `nameWithoutExtension` here on purpose - those are extension functions in
    // tachiyomi.core.common.storage. Stubbing them would hide a missing import.

    fun lastModified(): Long = 0
    fun exists(): Boolean = false
    fun listFiles(): Array<UniFile>? = null
    fun findFile(name: String): UniFile? = null
    fun createDirectory(name: String): UniFile? = null
    fun createFile(name: String): UniFile? = null
    fun openInputStream(): InputStream = throw UnsupportedOperationException("stub")
    fun openOutputStream(): OutputStream = throw UnsupportedOperationException("stub")

    /** Real signature: `openOutputStream(boolean)` - true opens SAF/raw mode "wa" (append). */
    fun openOutputStream(append: Boolean): OutputStream = throw UnsupportedOperationException("stub")
    fun delete(): Boolean = false

    companion object {
        @JvmStatic
        fun fromFile(context: Any?, file: File): UniFile? = null
    }
}
