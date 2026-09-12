package com.avscode.workspace

import com.avscode.core.AvsLogger
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorOutputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

enum class ArchiveFormat(val extension: String, val mimeType: String, val displayName: String) {
    ZIP(".zip", "application/zip", "ZIP Archive (.zip)"),
    TAR_GZ(".tar.gz", "application/gzip", "Tar Gzip (.tar.gz)"),
    TAR_XZ(".tar.xz", "application/x-xz", "Tar XZ (.tar.xz)");

    companion object {
        fun fromFileName(fileName: String): ArchiveFormat {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> TAR_GZ
                lower.endsWith(".tar.xz") || lower.endsWith(".txz") -> TAR_XZ
                lower.endsWith(".zip") -> ZIP
                else -> ZIP
            }
        }
    }
}

class WorkspaceArchiveManager(private val projectsDir: File) {

    init {
        if (!projectsDir.exists()) {
            projectsDir.mkdirs()
        }
    }

    /**
     * Lists existing project directories within /home/user/projects.
     */
    fun listProjects(): List<File> {
        if (!projectsDir.exists()) return emptyList()
        return projectsDir.listFiles { file -> file.isDirectory }?.toList()?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * Exports [sourceDir] to the provided [outputStream] using [format].
     * Uses streaming I/O to maintain low memory footprint.
     */
    fun exportProject(sourceDir: File, outputStream: OutputStream, format: ArchiveFormat) {
        require(sourceDir.exists()) { "Source directory does not exist: ${sourceDir.absolutePath}" }
        require(sourceDir.isDirectory) { "Source path is not a directory: ${sourceDir.absolutePath}" }

        val bufferedOut = BufferedOutputStream(outputStream, 65536)

        when (format) {
            ArchiveFormat.ZIP -> {
                ZipArchiveOutputStream(bufferedOut).use { zipOut ->
                    zipOut.setLevel(6)
                    addDirectoryToZip(sourceDir, sourceDir, zipOut)
                    zipOut.finish()
                }
            }
            ArchiveFormat.TAR_GZ -> {
                GzipCompressorOutputStream(bufferedOut).use { gzOut ->
                    TarArchiveOutputStream(gzOut).use { tarOut ->
                        tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                        tarOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                        addDirectoryToTar(sourceDir, sourceDir, tarOut)
                        tarOut.finish()
                    }
                }
            }
            ArchiveFormat.TAR_XZ -> {
                XZCompressorOutputStream(bufferedOut).use { xzOut ->
                    TarArchiveOutputStream(xzOut).use { tarOut ->
                        tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                        tarOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                        addDirectoryToTar(sourceDir, sourceDir, tarOut)
                        tarOut.finish()
                    }
                }
            }
        }
        bufferedOut.flush()
    }

    /**
     * Imports an archive from [inputStream] into [destinationDir].
     * Implements strict Zip-Slip path validation.
     */
    fun importArchive(
        inputStream: InputStream,
        destinationDir: File,
        format: ArchiveFormat
    ): Int {
        if (!destinationDir.exists()) {
            destinationDir.mkdirs()
        }
        val canonicalDest = destinationDir.canonicalFile
        val bufferedIn = BufferedInputStream(inputStream, 65536)
        var fileCount = 0

        when (format) {
            ArchiveFormat.ZIP -> {
                ZipArchiveInputStream(bufferedIn).use { zipIn ->
                    var entry: ZipArchiveEntry? = zipIn.nextEntry
                    while (entry != null) {
                        val outFile = File(canonicalDest, entry.name).canonicalFile
                        if (!outFile.path.startsWith(canonicalDest.path + File.separator) && outFile != canonicalDest) {
                            throw SecurityException("Zip Slip detected in archive entry: ${entry.name}")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { fos ->
                                zipIn.copyTo(fos, 32768)
                            }
                            if ((entry.unixMode and 0b001_000_000) != 0) {
                                outFile.setExecutable(true, false)
                            }
                            fileCount++
                        }
                        entry = zipIn.nextEntry
                    }
                }
            }
            ArchiveFormat.TAR_GZ -> {
                GzipCompressorInputStream(bufferedIn).use { gzIn ->
                    TarArchiveInputStream(gzIn).use { tarIn ->
                        fileCount = extractTarStream(tarIn, canonicalDest)
                    }
                }
            }
            ArchiveFormat.TAR_XZ -> {
                XZCompressorInputStream(bufferedIn).use { xzIn ->
                    TarArchiveInputStream(xzIn).use { tarIn ->
                        fileCount = extractTarStream(tarIn, canonicalDest)
                    }
                }
            }
        }
        return fileCount
    }

    private fun extractTarStream(tarIn: TarArchiveInputStream, canonicalDest: File): Int {
        var fileCount = 0
        var entry: TarArchiveEntry? = tarIn.nextEntry
        while (entry != null) {
            val outFile = File(canonicalDest, entry.name).canonicalFile
            if (!outFile.path.startsWith(canonicalDest.path + File.separator) && outFile != canonicalDest) {
                throw SecurityException("Zip Slip detected in tar entry: ${entry.name}")
            }
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else if (entry.isFile) {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { fos ->
                    tarIn.copyTo(fos, 32768)
                }
                if ((entry.mode and 0b001_000_000) != 0) {
                    outFile.setExecutable(true, false)
                }
                fileCount++
            }
            entry = tarIn.nextEntry
        }
        return fileCount
    }

    private fun addDirectoryToZip(root: File, current: File, zipOut: ZipArchiveOutputStream) {
        val files = current.listFiles() ?: return
        for (file in files) {
            val relativePath = file.relativeTo(root).path
            if (file.isDirectory) {
                val dirEntry = ZipArchiveEntry("$relativePath/")
                zipOut.putArchiveEntry(dirEntry)
                zipOut.closeArchiveEntry()
                addDirectoryToZip(root, file, zipOut)
            } else {
                val entry = ZipArchiveEntry(file, relativePath)
                if (file.canExecute()) {
                    entry.unixMode = 0b111_101_101 // 0755
                } else {
                    entry.unixMode = 0b110_100_100 // 0644
                }
                zipOut.putArchiveEntry(entry)
                FileInputStream(file).use { fis ->
                    fis.copyTo(zipOut, 32768)
                }
                zipOut.closeArchiveEntry()
            }
        }
    }

    private fun addDirectoryToTar(root: File, current: File, tarOut: TarArchiveOutputStream) {
        val files = current.listFiles() ?: return
        for (file in files) {
            val relativePath = file.relativeTo(root).path
            if (file.isDirectory) {
                val dirEntry = TarArchiveEntry("$relativePath/")
                tarOut.putArchiveEntry(dirEntry)
                tarOut.closeArchiveEntry()
                addDirectoryToTar(root, file, tarOut)
            } else {
                val entry = TarArchiveEntry(file, relativePath)
                if (file.canExecute()) {
                    entry.mode = 0b111_101_101 // 0755
                } else {
                    entry.mode = 0b110_100_100 // 0644
                }
                tarOut.putArchiveEntry(entry)
                FileInputStream(file).use { fis ->
                    fis.copyTo(tarOut, 32768)
                }
                tarOut.closeArchiveEntry()
            }
        }
    }
}
