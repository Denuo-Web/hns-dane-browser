package com.denuoweb.hnsdane.net

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

internal object GatewayResponseBodyStore {
    private const val DIRECTORY = "hns/response-bodies"
    private const val PREFIX = "hns-gateway-"
    private const val SUFFIX = ".body"
    private const val MAX_FILES = 64
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024
    private const val MAX_FILE_BYTES = 8L * 1024 * 1024
    private const val MAX_AGE_MILLIS = 24L * 60 * 60 * 1_000
    private val activeFiles = linkedSetOf<String>()

    @Synchronized
    fun create(dataDir: String): File? {
        forgetMissingLeases()
        val directory = File(dataDir, DIRECTORY)
        if (!directory.exists() && !directory.mkdirs()) {
            return null
        }
        pruneDirectory(directory)
        val files = matchingFiles(directory)
        val inactiveBytes = files
            .filterNot { it.absolutePath in activeFiles }
            .sumOf { it.length() }
        val reservedBytes = activeFiles.size.toLong() * MAX_FILE_BYTES
        if (
            files.size >= MAX_FILES ||
            activeFiles.size >= MAX_FILES ||
            inactiveBytes > MAX_TOTAL_BYTES - reservedBytes - MAX_FILE_BYTES
        ) {
            return null
        }
        return runCatching { File.createTempFile(PREFIX, SUFFIX, directory) }
            .getOrNull()
            ?.also { activeFiles += it.absolutePath }
    }

    @Synchronized
    fun retainCompleted(file: File): Boolean {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) {
            release(file)
            return false
        }
        val directory = file.parentFile ?: run {
            release(file)
            return false
        }
        pruneDirectory(directory, protectedFile = file)
        return file.isFile
    }

    @Synchronized
    fun release(file: File) {
        activeFiles -= file.absolutePath
        file.delete()
    }

    @Synchronized
    fun openReleasing(file: File): InputStream = try {
        ReleasingFileInputStream(file)
    } catch (error: Exception) {
        // Do not leave a phantom active lease behind when the file was removed by
        // the OS or external cleanup before WebView opened the response stream.
        release(file)
        throw error
    }

    @Synchronized
    fun prune(dataDir: String) {
        pruneDirectory(File(dataDir, DIRECTORY))
    }

    @Synchronized
    internal fun pruneDirectory(
        directory: File,
        nowMillis: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = MAX_AGE_MILLIS,
        maxFiles: Int = MAX_FILES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
        protectedFile: File? = null,
    ) {
        forgetMissingLeases()
        if (!directory.isDirectory) {
            return
        }
        val protectedPath = protectedFile?.absolutePath
        var retained = 0
        var retainedBytes = 0L
        matchingFiles(directory)
            .sortedByDescending(File::lastModified)
            .forEach { file ->
                val size = file.length().coerceAtLeast(0L)
                val expired = nowMillis - file.lastModified() > maxAgeMillis
                val isProtected = file.absolutePath == protectedPath || file.absolutePath in activeFiles
                val overBudget = retained >= maxFiles || retainedBytes > maxTotalBytes - size
                if (!isProtected && (expired || overBudget)) {
                    file.delete()
                } else if (file.isFile) {
                    retained += 1
                    retainedBytes += size
                }
            }
    }

    private fun matchingFiles(directory: File): List<File> =
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith(PREFIX) && it.name.endsWith(SUFFIX) }

    private fun forgetMissingLeases() {
        activeFiles.removeAll { path -> !File(path).isFile }
    }

    private class ReleasingFileInputStream(
        private val file: File,
    ) : FileInputStream(file) {
        override fun close() {
            try {
                super.close()
            } finally {
                release(file)
            }
        }
    }
}
