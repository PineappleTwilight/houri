package exh.log

import com.elvishew.xlog.internal.DefaultsFactory
import com.elvishew.xlog.printer.Printer
import com.elvishew.xlog.printer.file.backup.BackupStrategy
import com.elvishew.xlog.printer.file.naming.FileNameGenerator
import com.hippo.unifile.UniFile
import exh.log.EnhancedFilePrinter.Builder
import java.io.BufferedWriter
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import kotlin.time.Duration.Companion.days
import com.elvishew.xlog.flattener.Flattener2 as Flattener

/**
 * Log [Printer] using file system. When print a log, it will print it to the specified file.
 *
 * Use the [Builder] to construct a [EnhancedFilePrinter] object.
 *
 * @param folder The folder path of log file.
 * @param fileNameGenerator the file name generator for log file.
 * @param backupStrategy the backup strategy for log file.
 * @param cleanStrategy The clean strategy for log file.
 * @param flattener The flattener when print a log.
 *
 */
@Suppress("unused")
class EnhancedFilePrinter internal constructor(
    private val folder: UniFile,
    private val fileNameGenerator: FileNameGenerator,
    private val backupStrategy: BackupStrategy,
    private val flattener: Flattener,
) : Printer {
    /**
     * Log writer.
     */
    private val writer: Writer

    @Volatile
    private var worker: Worker? = null

    override fun println(logLevel: Int, tag: String, msg: String) {
        val timeMillis = System.currentTimeMillis()
        if (USE_WORKER) {
            val worker = worker ?: return
            if (!worker.isStarted()) {
                worker.start()
            }
            worker.enqueue(LogItem(timeMillis, logLevel, tag, msg))
        } else {
            doPrintln(timeMillis, logLevel, tag, msg)
        }
    }

    /**
     * Do the real job of writing log to file.
     */
    private fun doPrintln(timeMillis: Long, logLevel: Int, tag: String, msg: String) {
        val lastFileName = writer.lastFileName
        if (fileNameGenerator.isFileNameChangeable) {
            val newFileName = fileNameGenerator.generateFileName(logLevel, System.currentTimeMillis())
            require(
                !(
                    newFileName == null ||
                        newFileName.trim {
                            it <= ' '
                        }.isEmpty()
                    ),
            ) { "File name should not be empty." }
            if (newFileName != lastFileName) {
                if (writer.isOpened) {
                    writer.close()
                }
                cleanLogFilesIfNecessary()
                val file = folder.createFile(newFileName)
                if (file == null || writer.open(file).not()) {
                    return
                }
            }
        }
        val flattenedLog = flattener.flatten(timeMillis, logLevel, tag, msg).toString()
        writer.appendLog(flattenedLog)
    }

    private val maxTimeMillis = 7.days.inWholeMilliseconds
    private fun shouldClean(file: UniFile): Boolean {
        val currentTimeMillis = System.currentTimeMillis()
        val lastModified = file.lastModified()
        return currentTimeMillis - lastModified > maxTimeMillis
    }

    /**
     * Clean log files if should clean follow strategy
     */
    private fun cleanLogFilesIfNecessary() {
        folder.listFiles().orEmpty()
            .asSequence()
            .filter { shouldClean(it) }
            .forEach { it.delete() }
    }

    /**
     * Builder for [EnhancedFilePrinter].
     * @param folderPath the folder path of log file
     */
    class Builder(private val folder: UniFile) {
        /**
         * The file name generator for log file.
         */
        var fileNameGenerator: FileNameGenerator? = null

        /**
         * The backup strategy for log file.
         */
        var backupStrategy: BackupStrategy? = null

        /**
         * The flattener when print a log.
         */
        var flattener: Flattener? = null

        /**
         * Set the file name generator for log file.
         *
         * @param fileNameGenerator the file name generator for log file
         * @return the builder
         */
        fun fileNameGenerator(fileNameGenerator: FileNameGenerator): Builder {
            this.fileNameGenerator = fileNameGenerator
            return this
        }

        /**
         * Set the backup strategy for log file.
         *
         * @param backupStrategy the backup strategy for log file
         * @return the builder
         */
        fun backupStrategy(backupStrategy: BackupStrategy): Builder {
            this.backupStrategy = backupStrategy
            return this
        }

        /**
         * Set the flattener when print a log.
         *
         * @param flattener the flattener when print a log
         * @return the builder
         */
        fun flattener(flattener: Flattener): Builder {
            this.flattener = flattener
            return this
        }

        /**
         * Build configured [EnhancedFilePrinter] object.
         *
         * @return the built configured [EnhancedFilePrinter] object
         */
        fun build(): EnhancedFilePrinter {
            return EnhancedFilePrinter(
                folder,
                fileNameGenerator ?: DefaultsFactory.createFileNameGenerator(),
                backupStrategy ?: DefaultsFactory.createBackupStrategy(),
                flattener ?: DefaultsFactory.createFlattener2(),
            )
        }

        companion object {
            operator fun invoke(folder: UniFile, block: Builder.() -> Unit): EnhancedFilePrinter {
                return Builder(folder).apply(block).build()
            }
        }
    }

    private data class LogItem(
        var timeMillis: Long,
        var level: Int,
        var tag: String,
        var msg: String,
    )

    /**
     * Work in background, we can enqueue the logs, and the worker will dispatch them.
     */
    private inner class Worker : Runnable {
        private val logs: BlockingQueue<LogItem> = ArrayBlockingQueue(MAX_PENDING_LOGS)

        @Volatile
        private var started = false

        @Volatile
        var dropped = 0
            private set

        /**
         * Enqueue the log.
         *
         * @param log the log to be written to file
         */
        fun enqueue(log: LogItem) {
            try {
                // Never block the caller: losing a log line beats stalling whichever thread logged it.
                if (!logs.offer(log)) logs.poll()?.let { dropped++ }
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }

        /**
         * Whether the worker is started.
         *
         * @return true if started, false otherwise
         */
        fun isStarted(): Boolean {
            synchronized(this) { return started }
        }

        /**
         * Start the worker.
         */
        fun start() {
            synchronized(this) {
                if (started) return
                Thread(this).start()
                started = true
            }
        }

        override fun run() {
            try {
                while (true) {
                    val log = logs.take()
                    doPrintln(log.timeMillis, log.level, log.tag, log.msg)
                }
            } catch (e: InterruptedException) {
                e.printStackTrace()
            } catch (e: Throwable) {
                // A failed write must not take the worker down for good: run() is only ever
                // re-entered via isStarted(), so dying here would silently funnel every later log
                // line into a queue nobody drains, growing until the process runs out of memory.
                e.printStackTrace()
            } finally {
                synchronized(this) { started = false }
            }
        }
    }

    /**
     * Used to write the flattened logs to the log file.
     */
    private inner class Writer {
        /**
         * Get the name of last used log file.
         * @return the name of last used log file, maybe null
         */
        var lastFileName: String? = null
            private set

        /**
         * Get the current log file.
         *
         * @return the current log file, maybe null
         */
        var file: UniFile? = null
            private set

        private var bufferedWriter: BufferedWriter? = null

        /**
         * Whether the log file is opened.
         *
         * @return true if opened, false otherwise
         */
        val isOpened: Boolean
            get() = bufferedWriter != null

        /**
         * Open the file of specific name to be written into.
         *
         * @param newFileName the specific file name
         * @return true if opened successfully, false otherwise
         */
        fun open(file: UniFile): Boolean {
            return try {
                // KMK -->
                // Append, like XLog's own SimpleWriter (`new FileWriter(file, true)`): the no-arg
                // openOutputStream() opens SAF/raw mode "w", which TRUNCATES - so every process
                // start wiped the current day's log down to that session's boot lines.
                bufferedWriter = file.openOutputStream(true).bufferedWriter()
                // KMK <--
                lastFileName = file.name
                this.file = file
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        /**
         * Close the current log file if it is opened.
         *
         * @return true if closed successfully, false otherwise
         */
        fun close(): Boolean {
            if (bufferedWriter != null) {
                try {
                    bufferedWriter?.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                    return false
                } finally {
                    bufferedWriter = null
                    lastFileName = null
                    file = null
                }
            }
            return true
        }

        /**
         * Append the flattened log to the end of current opened log file.
         *
         * @param flattenedLog the flattened log
         */
        fun appendLog(flattenedLog: String) {
            val bufferedWriter = bufferedWriter
            requireNotNull(bufferedWriter)
            try {
                bufferedWriter.write(flattenedLog)
                bufferedWriter.newLine()
                bufferedWriter.flush()
            } catch (e: IOException) {
            }
        }
    }

    companion object {
        /**
         * Use worker, write logs asynchronously.
         */
        private const val USE_WORKER = true

        /**
         * Cap on queued log lines. Bounds the damage if the writer stalls or dies: without it a
         * body-level log flood (e.g. E-Hentai's request interceptor) grows the queue without limit.
         */
        private const val MAX_PENDING_LOGS = 4096
    }

    init {
        writer = Writer()
        if (USE_WORKER) {
            worker = Worker()
        }
    }
}
