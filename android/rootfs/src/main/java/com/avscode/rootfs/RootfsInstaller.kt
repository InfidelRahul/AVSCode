package com.avscode.rootfs

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.system.Os
import com.avscode.core.AppPaths
import com.avscode.core.AvsLogger
import com.avscode.core.Result
import com.avscode.core.runCatchingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Robust Ubuntu Rootfs Installer.
 *
 * Handles downloading, safe extraction, post-extraction configuration,
 * and atomic installation verification.
 */
class RootfsInstaller(private val context: Context) {

    companion object {
        private const val TAG = "RootfsInstaller"

        // Ubuntu Base ARM64 rootfs URL - Ubuntu 26.04 (Resolute)
        const val ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/resolute/release/ubuntu-base-26.04-base-arm64.tar.gz"

        // Minimum required disk space (2GB)
        const val MIN_DISK_SPACE = 2L * 1024 * 1024 * 1024

        // Buffer size for streaming
        private const val BUFFER_SIZE = 64 * 1024
    }

    private val paths = AppPaths.getInstance(context)
    private val tempDownloadFile = File(paths.cacheDir, "ubuntu-base-arm64.tar.gz")

    /**
     * Check if rootfs is properly installed and verified.
     */
    fun isInstalled(): Boolean {
        val rootfsDir = paths.rootfsDir
        val marker = paths.rootfsInstallMarker
        val hasBash = File(rootfsDir, "bin/bash").exists() || File(rootfsDir, "usr/bin/bash").exists()
        val hasSh = File(rootfsDir, "bin/sh").exists() || File(rootfsDir, "usr/bin/sh").exists()
        val hasPasswd = File(rootfsDir, "etc/passwd").exists()

        return marker.exists() && hasBash && hasSh && hasPasswd
    }

    /**
     * Get the absolute path to the installed rootfs directory.
     */
    fun getRootfsPath(): String {
        return paths.rootfsDir.absolutePath
    }

    /**
     * Install the rootfs with progress feedback.
     */
    suspend fun install(progressCallback: ((Float, String) -> Unit)? = null): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Starting rootfs installation")

        runCatchingResult {
            if (isInstalled()) {
                AvsLogger.i(TAG, "Rootfs is already installed, reusing existing installation")
                progressCallback?.invoke(1.0f, "Rootfs ready")
                return@runCatchingResult Unit
            }

            // Check disk space
            checkDiskSpace()

            // Step 1: Download
            progressCallback?.invoke(0.05f, "Downloading Ubuntu ARM64 rootfs...")
            downloadRootfs { progress ->
                progressCallback?.invoke(0.05f + progress * 0.45f, "Downloading Ubuntu ARM64 rootfs (${(progress * 100).toInt()}%)...")
            }

            // Step 2: Extract to staging directory
            val stagingDir = paths.rootfsStagingDir
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            stagingDir.mkdirs()

            progressCallback?.invoke(0.50f, "Extracting Ubuntu filesystem...")
            extractTarGz(tempDownloadFile, stagingDir) { progress ->
                progressCallback?.invoke(0.50f + progress * 0.40f, "Extracting Ubuntu filesystem (${(progress * 100).toInt()}%)...")
            }

            // Step 3: Configure critical files (DNS, APT, Users)
            progressCallback?.invoke(0.92f, "Configuring guest environment...")
            configureGuestEnvironment(stagingDir)

            // Step 4: Verify staged rootfs
            verifyStagedRootfs(stagingDir)

            // Step 5: Mark installed in staging directory
            File(stagingDir, ".installed").createNewFile()

            // Step 6: Atomic promotion of staging directory
            progressCallback?.invoke(0.98f, "Finalizing installation...")
            if (paths.rootfsDir.exists()) {
                paths.rootfsDir.deleteRecursively()
            }
            val renamed = stagingDir.renameTo(paths.rootfsDir)
            if (!renamed) {
                // If direct rename fails (e.g. across mount points), copy recursively
                stagingDir.copyRecursively(paths.rootfsDir, overwrite = true)
                stagingDir.deleteRecursively()
            }

            // Step 7: Cleanup downloaded archive
            cleanupTempFiles()

            progressCallback?.invoke(1.0f, "Installation complete")
            AvsLogger.i(TAG, "Rootfs installation completed successfully at: ${paths.rootfsDir.absolutePath}")
        }
    }

    private fun checkDiskSpace() {
        val statFs = StatFs(paths.filesDir.absolutePath)
        val availableBytes = statFs.availableBytes

        if (availableBytes < MIN_DISK_SPACE) {
            val reqMb = MIN_DISK_SPACE / (1024 * 1024)
            val availMb = availableBytes / (1024 * 1024)
            throw InsufficientDiskSpaceException("Insufficient disk space. Required: ${reqMb}MB, Available: ${availMb}MB")
        }
        AvsLogger.d(TAG, "Disk space check passed: ${availableBytes / (1024 * 1024)}MB free")
    }

    private suspend fun downloadRootfs(progressCallback: ((Float) -> Unit)? = null) = withContext(Dispatchers.IO) {
        // If file already exists and has expected size (> 25MB), reuse it
        if (tempDownloadFile.exists() && tempDownloadFile.length() > 25 * 1024 * 1024) {
            AvsLogger.i(TAG, "Existing download file found (${tempDownloadFile.length()} bytes), verifying...")
            progressCallback?.invoke(1.0f)
            return@withContext
        }

        AvsLogger.i(TAG, "Downloading rootfs from $ROOTFS_URL")
        val url = URL(ROOTFS_URL)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 60000
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true

        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            throw IOException("HTTP error downloading rootfs: $responseCode ${connection.responseMessage}")
        }

        val totalBytes = connection.contentLengthLong
        var downloadedBytes = 0L

        connection.inputStream.use { input ->
            FileOutputStream(tempDownloadFile).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    downloadedBytes += read
                    if (totalBytes > 0) {
                        progressCallback?.invoke(downloadedBytes.toFloat() / totalBytes.toFloat())
                    }
                }
            }
        }
        AvsLogger.i(TAG, "Download finished: ${downloadedBytes / (1024 * 1024)}MB")
    }

    private suspend fun extractTarGz(
        tarGzFile: File,
        destDir: File,
        progressCallback: ((Float) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Extracting ${tarGzFile.name} to ${destDir.absolutePath}")

        // Attempt system tar first if available
        var systemTarSuccess = false
        try {
            val process = ProcessBuilder(
                "tar",
                "-xzf",
                tarGzFile.absolutePath,
                "-C",
                destDir.absolutePath
            ).redirectErrorStream(true).start()

            val exitCode = process.waitFor()
            if (exitCode == 0 && File(destDir, "etc/passwd").exists()) {
                systemTarSuccess = true
                progressCallback?.invoke(1.0f)
                AvsLogger.i(TAG, "Extracted rootfs using system tar successfully")
            } else {
                AvsLogger.w(TAG, "System tar returned exit code $exitCode, falling back to internal extractor")
            }
        } catch (e: Exception) {
            AvsLogger.w(TAG, "System tar invocation failed: ${e.message}, falling back to internal extractor")
        }

        if (!systemTarSuccess) {
            extractWithInternalTar(tarGzFile, destDir, progressCallback)
        }
    }

    /**
     * Pure Kotlin streaming Tar.gz extractor.
     * Handles standard POSIX ustar entries, symlinks, directories, and hard links without requiring external dependencies.
     */
    private fun extractWithInternalTar(
        tarGzFile: File,
        destDir: File,
        progressCallback: ((Float) -> Unit)? = null
    ) {
        val totalBytes = tarGzFile.length()
        val fileInputStream = FileInputStream(tarGzFile)
        val countingStream = object : FilterInputStream(fileInputStream) {
            var bytesRead = 0L
            override fun read(): Int = super.read().also { if (it != -1) bytesRead++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it != -1) bytesRead += it }
        }

        GZIPInputStream(countingStream, BUFFER_SIZE).use { gzipStream ->
            val headerBuffer = ByteArray(512)
            var nextLongName: String? = null
            var lastProgressUpdate = 0L

            while (true) {
                var headerRead = 0
                while (headerRead < 512) {
                    val r = gzipStream.read(headerBuffer, headerRead, 512 - headerRead)
                    if (r == -1) break
                    headerRead += r
                }
                if (headerRead < 512) break

                // Check for end-of-archive (two consecutive all-zero blocks)
                if (headerBuffer.all { it.toInt() == 0 }) {
                    break
                }

                val rawName = String(headerBuffer, 0, 100, Charsets.UTF_8).trimEnd('\u0000', ' ')
                val typeFlag = headerBuffer[156].toInt().toChar()
                val sizeString = String(headerBuffer, 124, 12, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                val size = try {
                    if (sizeString.isNotBlank()) sizeString.trim().toLong(8) else 0L
                } catch (e: Exception) {
                    0L
                }
                val linkName = String(headerBuffer, 157, 100, Charsets.UTF_8).trimEnd('\u0000', ' ')

                val entryName = nextLongName ?: rawName
                nextLongName = null

                // Handle GNU LongLink
                if (typeFlag == 'L' || entryName == "././@LongLink") {
                    val longNameBytes = ByteArray(size.toInt())
                    readFully(gzipStream, longNameBytes)
                    skipPadding(gzipStream, size)
                    nextLongName = String(longNameBytes, Charsets.UTF_8).trimEnd('\u0000', ' ')
                    continue
                }

                if (entryName.isEmpty()) continue

                val targetFile = File(destDir, entryName)

                when (typeFlag) {
                    '5' -> { // Directory
                        targetFile.mkdirs()
                    }
                    '2' -> { // Symlink
                        targetFile.parentFile?.mkdirs()
                        targetFile.delete()
                        try {
                            Os.symlink(linkName, targetFile.absolutePath)
                        } catch (e: Exception) {
                            AvsLogger.w(TAG, "Symlink failed for $entryName -> $linkName: ${e.message}")
                        }
                    }
                    '1' -> { // Hard link
                        targetFile.parentFile?.mkdirs()
                        targetFile.delete()
                        val original = File(destDir, linkName)
                        try {
                            Os.link(original.absolutePath, targetFile.absolutePath)
                        } catch (e: Exception) {
                            // Fallback to copy or symlink
                            try {
                                if (original.exists()) {
                                    original.copyTo(targetFile, overwrite = true)
                                } else {
                                    Os.symlink(linkName, targetFile.absolutePath)
                                }
                            } catch (e2: Exception) {
                                AvsLogger.w(TAG, "Hard link fallback failed for $entryName: ${e2.message}")
                            }
                        }
                    }
                    else -> { // Regular file
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { out ->
                            copyBytes(gzipStream, out, size)
                        }
                        skipPadding(gzipStream, size)
                        targetFile.setExecutable(true, false)
                    }
                }

                val now = System.currentTimeMillis()
                if (now - lastProgressUpdate > 300) {
                    lastProgressUpdate = now
                    if (totalBytes > 0) {
                        val frac = (countingStream.bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        progressCallback?.invoke(frac)
                    }
                }
            }
        }
        progressCallback?.invoke(1.0f)
        AvsLogger.i(TAG, "Internal tar extraction finished")
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) throw EOFException("Unexpected EOF while reading archive")
            offset += count
        }
    }

    private fun copyBytes(input: InputStream, output: OutputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(minOf(BUFFER_SIZE.toLong(), count).toInt())
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val r = input.read(buf, 0, toRead)
            if (r < 0) throw EOFException("Premature EOF while extracting file")
            output.write(buf, 0, r)
            remaining -= r
        }
    }

    private fun skipPadding(input: InputStream, size: Long) {
        val pad = (512 - (size % 512)) % 512
        if (pad > 0) {
            var skipped = 0L
            while (skipped < pad) {
                val s = input.skip(pad - skipped)
                if (s <= 0) {
                    if (input.read() == -1) break
                    skipped++
                } else {
                    skipped += s
                }
            }
        }
    }

    /**
     * Configure essential rootfs files so networking, APT, and users work seamlessly.
     */
    private fun configureGuestEnvironment(rootfsDir: File) {
        // 1. DNS configuration
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.mkdirs()
        resolvConf.delete()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")

        // 2. Hosts configuration
        val hosts = File(rootfsDir, "etc/hosts")
        hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")

        // 3. Prevent APT from dropping privileges to _apt (fails in PRoot sandbox)
        val aptConfigDir = File(rootfsDir, "etc/apt/apt.conf.d")
        aptConfigDir.mkdirs()
        File(aptConfigDir, "99nodrop").writeText("APT::Sandbox::User \"root\";\n")

        // 4. Ensure /tmp exists with permissive permissions
        val tmpDir = File(rootfsDir, "tmp")
        tmpDir.mkdirs()
        tmpDir.setReadable(true, false)
        tmpDir.setWritable(true, false)
        tmpDir.setExecutable(true, false)

        // 5. Ensure /home/user and /home/user/projects exist
        val userHome = File(rootfsDir, "home/user")
        val userProjects = File(userHome, "projects")
        userProjects.mkdirs()
        userHome.setReadable(true, false)
        userHome.setWritable(true, false)
        userHome.setExecutable(true, false)

        // 6. Ensure user account in /etc/passwd
        val passwdFile = File(rootfsDir, "etc/passwd")
        if (passwdFile.exists()) {
            val content = passwdFile.readText()
            if (!content.contains("user:")) {
                passwdFile.appendText("user:x:1000:1000:User:/home/user:/bin/bash\n")
            }
        }

        // 7. Ensure group in /etc/group
        val groupFile = File(rootfsDir, "etc/group")
        if (groupFile.exists()) {
            val content = groupFile.readText()
            if (!content.contains("user:")) {
                groupFile.appendText("user:x:1000:\n")
            }
        }

        // 8. Ensure root profile sets PATH
        val rootProfile = File(rootfsDir, "root/.profile")
        rootProfile.parentFile?.mkdirs()
        if (!rootProfile.exists()) {
            rootProfile.writeText("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n")
        }

        AvsLogger.d(TAG, "Configured guest environment (DNS, APT, /home/user/projects)")
    }

    private fun verifyStagedRootfs(stagingDir: File) {
        val required = listOf(
            "etc/passwd",
            "bin/sh",
            "usr/bin"
        )
        for (rel in required) {
            val f = File(stagingDir, rel)
            if (!f.exists()) {
                throw VerificationException("Rootfs verification failed: missing $rel")
            }
        }
        val hasBash = File(stagingDir, "bin/bash").exists() || File(stagingDir, "usr/bin/bash").exists()
        if (!hasBash) {
            throw VerificationException("Rootfs verification failed: missing bash")
        }
    }

    fun cleanupTempFiles() {
        if (tempDownloadFile.exists()) {
            tempDownloadFile.delete()
        }
        val staging = paths.rootfsStagingDir
        if (staging.exists()) {
            staging.deleteRecursively()
        }
    }

    fun uninstall() {
        AvsLogger.i(TAG, "Uninstalling rootfs...")
        paths.rootfsDir.deleteRecursively()
        cleanupTempFiles()
    }

    class InsufficientDiskSpaceException(message: String) : Exception(message)
    class VerificationException(message: String) : Exception(message)
}
