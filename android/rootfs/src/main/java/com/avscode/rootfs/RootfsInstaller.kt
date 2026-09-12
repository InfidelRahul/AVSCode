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
 * Android host responsibility:
 * 1. Download Ubuntu 26.04 Base ARM64 archive.
 * 2. Extract filesystem preserving POSIX modes, permissions, symlinks, and hardlinks.
 * 3. Verify Linux directory structure (/bin, /usr, /etc, /home, /tmp) and essential binaries.
 * 4. Configure guest networking (resolv.conf, hosts), APT privileges, user, and deploy bootstrap.sh.
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

        // POSIX file modes in hex
        private const val MODE_755 = 0x1ED // 0755
        private const val MODE_644 = 0x1A4 // 0644
        private const val MODE_EXEC_BITS = 0x49 // 0111
        private const val MODE_1777 = 0x3FF // 01777
    }

    private val paths = AppPaths.getInstance(context)
    private val tempDownloadFile = File(paths.cacheDir, "ubuntu-base-arm64.tar.gz")

    /**
     * Check if rootfs is properly installed, complete, and verified.
     */
    fun isInstalled(): Boolean {
        val rootfsDir = paths.rootfsDir
        val marker = paths.rootfsInstallMarker
        val hasBash = File(rootfsDir, "bin/bash").exists() || File(rootfsDir, "usr/bin/bash").exists()
        val hasSh = File(rootfsDir, "bin/sh").exists() || File(rootfsDir, "usr/bin/sh").exists()
        val hasPasswd = File(rootfsDir, "etc/passwd").exists()
        val hasMkdir = File(rootfsDir, "usr/bin/mkdir").exists()

        return marker.exists() && hasBash && hasSh && hasPasswd && hasMkdir
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
        AvsLogger.i(TAG, "Starting rootfs installation on Android host")

        runCatchingResult {
            if (isInstalled()) {
                AvsLogger.i(TAG, "Rootfs is already installed and verified, reusing existing installation")
                progressCallback?.invoke(1.0f, "Rootfs ready")
                return@runCatchingResult Unit
            }

            // Check disk space
            checkDiskSpace()

            // Step 1: Download
            progressCallback?.invoke(0.05f, "Downloading Ubuntu 26.04 ARM64 rootfs...")
            downloadRootfs { progress ->
                progressCallback?.invoke(0.05f + progress * 0.45f, "Downloading Ubuntu 26.04 ARM64 rootfs (${(progress * 100).toInt()}%)...")
            }

            // Step 2: Extract to staging directory
            val stagingDir = paths.rootfsStagingDir
            if (stagingDir.exists()) {
                stagingDir.deleteRecursively()
            }
            stagingDir.mkdirs()
            ensureDirTraversable(stagingDir)

            progressCallback?.invoke(0.50f, "Extracting Ubuntu filesystem...")
            extractTarGz(tempDownloadFile, stagingDir) { progress ->
                progressCallback?.invoke(0.50f + progress * 0.40f, "Extracting Ubuntu filesystem (${(progress * 100).toInt()}%)...")
            }

            // Step 3: Configure critical files (DNS, APT, Users, bootstrap script)
            progressCallback?.invoke(0.92f, "Configuring guest environment...")
            configureGuestEnvironment(stagingDir)

            // Step 4: Verify staged rootfs and repair any broken core utils
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
                stagingDir.copyRecursively(paths.rootfsDir, overwrite = true)
                stagingDir.deleteRecursively()
            }

            // Step 7: Cleanup downloaded archive
            cleanupTempFiles()

            progressCallback?.invoke(1.0f, "Rootfs installation complete")
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
        if (tempDownloadFile.exists() && tempDownloadFile.length() > 25 * 1024 * 1024) {
            AvsLogger.i(TAG, "Existing download file found (${tempDownloadFile.length()} bytes), reusing")
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
        extractWithInternalTar(tarGzFile, destDir, progressCallback)
    }

    /**
     * Pure Kotlin streaming Tar.gz extractor with full POSIX permissions, symlink,
     * and hard link preservation.
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
            var nextLongLink: String? = null
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
                val modeString = String(headerBuffer, 100, 8, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                val mode = try {
                    if (modeString.isNotBlank()) modeString.trim().toInt(8) else 0
                } catch (e: Exception) {
                    0
                }
                val sizeString = String(headerBuffer, 124, 12, Charsets.US_ASCII).trimEnd('\u0000', ' ')
                val size = try {
                    if (sizeString.isNotBlank()) sizeString.trim().toLong(8) else 0L
                } catch (e: Exception) {
                    0L
                }
                val rawLinkName = String(headerBuffer, 157, 100, Charsets.UTF_8).trimEnd('\u0000', ' ')

                // Handle GNU LongLink / LongName extensions
                if (typeFlag == 'L' || rawName == "././@LongLink") {
                    val longNameBytes = ByteArray(size.toInt())
                    readFully(gzipStream, longNameBytes)
                    skipPadding(gzipStream, size)
                    nextLongName = String(longNameBytes, Charsets.UTF_8).trimEnd('\u0000', ' ')
                    continue
                }
                if (typeFlag == 'K') {
                    val longLinkBytes = ByteArray(size.toInt())
                    readFully(gzipStream, longLinkBytes)
                    skipPadding(gzipStream, size)
                    nextLongLink = String(longLinkBytes, Charsets.UTF_8).trimEnd('\u0000', ' ')
                    continue
                }

                val entryName = (nextLongName ?: rawName).removePrefix("./")
                nextLongName = null
                val linkName = (nextLongLink ?: rawLinkName).removePrefix("./")
                nextLongLink = null

                if (entryName.isEmpty()) continue

                val targetFile = File(destDir, entryName)

                when (typeFlag) {
                    '5' -> { // Directory
                        targetFile.mkdirs()
                        ensureDirTraversable(targetFile)
                        try {
                            Os.chmod(targetFile.absolutePath, if (mode != 0) (mode or MODE_755) else MODE_755)
                        } catch (e: Exception) {}
                    }
                    '2' -> { // Symlink
                        targetFile.parentFile?.let { ensureDirTraversable(it) }
                        targetFile.delete()
                        try {
                            Os.symlink(linkName, targetFile.absolutePath)
                        } catch (e: Exception) {
                            AvsLogger.w(TAG, "Symlink failed for $entryName -> $linkName: ${e.message}")
                        }
                    }
                    '1' -> { // Hard link
                        targetFile.parentFile?.let { ensureDirTraversable(it) }
                        targetFile.delete()
                        val cleanLink = linkName.removePrefix("/")
                        val original = File(destDir, cleanLink)
                        var linked = false
                        try {
                            Os.link(original.absolutePath, targetFile.absolutePath)
                            linked = true
                        } catch (e: Exception) {
                            // Direct Os.link might fail across mount types
                        }

                        if (!linked) {
                            try {
                                if (original.exists()) {
                                    original.copyTo(targetFile, overwrite = true)
                                    targetFile.setReadable(true, false)
                                    targetFile.setExecutable(original.canExecute() || (mode and MODE_EXEC_BITS != 0), false)
                                    try {
                                        Os.chmod(targetFile.absolutePath, if (mode != 0) mode else MODE_755)
                                    } catch (e: Exception) {}
                                } else {
                                    // Fallback to relative symlink inside rootfs
                                    Os.symlink(cleanLink, targetFile.absolutePath)
                                }
                            } catch (e2: Exception) {
                                AvsLogger.w(TAG, "Hard link fallback failed for $entryName: ${e2.message}")
                            }
                        }
                    }
                    else -> { // Regular file ('0', '\u0000', etc.)
                        targetFile.parentFile?.let { ensureDirTraversable(it) }
                        FileOutputStream(targetFile).use { out ->
                            copyBytes(gzipStream, out, size)
                        }
                        skipPadding(gzipStream, size)
                        targetFile.setReadable(true, false)

                        val isExecutable = (mode and MODE_EXEC_BITS != 0) ||
                                entryName.startsWith("bin/") ||
                                entryName.startsWith("usr/bin/") ||
                                entryName.startsWith("sbin/") ||
                                entryName.startsWith("usr/sbin/") ||
                                entryName.contains("/bin/")

                        if (isExecutable) {
                            targetFile.setExecutable(true, false)
                        }

                        try {
                            val targetMode = if (mode != 0) mode else if (isExecutable) MODE_755 else MODE_644
                            Os.chmod(targetFile.absolutePath, targetMode)
                        } catch (e: Exception) {}
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
        AvsLogger.i(TAG, "Internal tar extraction finished with POSIX modes and permissions applied")
    }

    private fun ensureDirTraversable(dir: File) {
        if (!dir.exists()) {
            dir.mkdirs()
        }
        dir.setReadable(true, false)
        dir.setWritable(true, false)
        dir.setExecutable(true, false)
        try {
            Os.chmod(dir.absolutePath, MODE_755)
        } catch (e: Exception) {}
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
     * Configure essential rootfs files so networking, APT, users, and guest bootstrap work seamlessly.
     */
    private fun configureGuestEnvironment(rootfsDir: File) {
        // 1. DNS configuration
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.let { ensureDirTraversable(it) }
        resolvConf.delete()
        resolvConf.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        resolvConf.setReadable(true, false)

        // 2. Hosts configuration
        val hosts = File(rootfsDir, "etc/hosts")
        hosts.writeText("127.0.0.1 localhost\n::1 localhost\n")
        hosts.setReadable(true, false)

        // 3. Prevent APT from dropping privileges to _apt (fails in PRoot sandbox)
        val aptConfigDir = File(rootfsDir, "etc/apt/apt.conf.d")
        ensureDirTraversable(aptConfigDir)
        File(aptConfigDir, "99nodrop").writeText("APT::Sandbox::User \"root\";\n")
        File(aptConfigDir, "99nolanguages").writeText("Acquire::Languages \"none\";\n")

        // 4. Ensure guest /tmp exists with 1777 permissions
        val tmpDir = File(rootfsDir, "tmp")
        ensureDirTraversable(tmpDir)
        tmpDir.setWritable(true, false)
        try {
            Os.chmod(tmpDir.absolutePath, MODE_1777)
        } catch (e: Exception) {}

        // 5. Ensure /home/user and /home/user/projects exist
        val userHome = File(rootfsDir, "home/user")
        val userProjects = File(userHome, "projects")
        ensureDirTraversable(userHome)
        ensureDirTraversable(userProjects)

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
        rootProfile.parentFile?.let { ensureDirTraversable(it) }
        if (!rootProfile.exists()) {
            rootProfile.writeText("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n")
        }

        // 9. Deploy guest-side bootstrap script /usr/local/lib/avscode/bootstrap.sh
        deployGuestBootstrapScript(rootfsDir)

        AvsLogger.d(TAG, "Configured guest environment (DNS, APT, /home/user/projects, bootstrap.sh)")
    }

    /**
     * Deploys the Linux-side bootstrap script into the rootfs.
     */
    private fun deployGuestBootstrapScript(rootfsDir: File) {
        val scriptDir = File(rootfsDir, "usr/local/lib/avscode")
        ensureDirTraversable(scriptDir)
        val scriptFile = File(scriptDir, "bootstrap.sh")

        val scriptContent = """
            |#!/bin/bash
            |set -eo pipefail
            |
            |echo "=================================================="
            |echo "    AVSCode Linux Environment Bootstrap"
            |echo "=================================================="
            |
            |# 1. Validate the guest environment
            |echo "[1/6] Validating Linux guest environment..."
            |ROOT_ID="${'$'}(id -u)"
            |if [ "${'$'}ROOT_ID" -ne 0 ]; then
            |    echo "ERROR: Must run inside PRoot root context (uid 0, got ${'$'}ROOT_ID)" >&2
            |    exit 1
            |fi
            |
            |export DEBIAN_FRONTEND=noninteractive
            |export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
            |
            |# Verify essential utilities
            |for tool in bash mkdir tar rm chmod cat; do
            |    if ! command -v "${'$'}tool" >/dev/null 2>&1; then
            |        echo "ERROR: Missing essential utility: ${'$'}tool" >&2
            |        exit 2
            |    fi
            |done
            |echo "Guest utilities verified: mkdir=${'$'}(command -v mkdir), tar=${'$'}(command -v tar)"
            |
            |# 2. Configure APT and DNS
            |echo "[2/6] Configuring APT package manager..."
            |mkdir -p /etc/apt/apt.conf.d
            |cat <<'EOF' > /etc/apt/apt.conf.d/99avscode
            |APT::Sandbox::User "root";
            |Acquire::Languages "none";
            |Acquire::Retries "3";
            |EOF
            |
            |if [ ! -s /etc/resolv.conf ]; then
            |    echo "nameserver 8.8.8.8" > /etc/resolv.conf
            |    echo "nameserver 1.1.1.1" >> /etc/resolv.conf
            |fi
            |
            |# 3. Update package indexes
            |echo "[3/6] Updating APT package repositories..."
            |apt-get update -qq || {
            |    echo "WARNING: apt-get update returned non-zero, retrying..."
            |    apt-get update
            |}
            |
            |# 4. Install required base & development tools
            |echo "[4/6] Installing core tools (ca-certificates, curl, wget, git, python3)..."
            |apt-get install -y --no-install-recommends \
            |    ca-certificates \
            |    curl \
            |    wget \
            |    git \
            |    python3 \
            |    procps || {
            |    echo "ERROR: Failed to install core development packages" >&2
            |    exit 3
            |}
            |
            |# 5. Create / configure Linux user 'user'
            |echo "[5/6] Configuring Linux user environment..."
            |if ! id -u user >/dev/null 2>&1; then
            |    useradd -m -s /bin/bash user || true
            |fi
            |mkdir -p /home/user/projects /home/user/.local/share/code-server /tmp
            |chmod 1777 /tmp
            |chown -R user:user /home/user || true
            |chmod 755 /home/user
            |
            |if [ ! -f /home/user/.profile ]; then
            |    cat <<'EOF' > /home/user/.profile
            |export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/code-server/bin
            |export SHELL=/bin/bash
            |export LANG=C.UTF-8
            |EOF
            |    chown user:user /home/user/.profile || true
            |fi
            |
            |# 6. Mark bootstrap complete
            |mkdir -p /var/lib/avscode
            |touch /var/lib/avscode/bootstrapped
            |echo "=================================================="
            |echo "    AVSCode Linux Bootstrap SUCCESSFUL"
            |echo "=================================================="
            |exit 0
        """.trimMargin()

        scriptFile.writeText(scriptContent)
        scriptFile.setReadable(true, false)
        scriptFile.setExecutable(true, false)
        try {
            Os.chmod(scriptFile.absolutePath, MODE_755)
        } catch (e: Exception) {}
    }

    private fun verifyStagedRootfs(stagingDir: File) {
        val required = listOf(
            "etc/passwd",
            "bin/sh",
            "usr/bin",
            "tmp"
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

        // Verify and repair usr/bin/mkdir if needed
        val mkdirFile = File(stagingDir, "usr/bin/mkdir")
        if (!mkdirFile.exists() || !mkdirFile.canExecute()) {
            AvsLogger.w(TAG, "usr/bin/mkdir missing or not executable in staging, repairing...")
            val coreutils = File(stagingDir, "usr/bin/coreutils")
            val gnumkdir = File(stagingDir, "usr/bin/gnumkdir")
            when {
                gnumkdir.exists() -> {
                    mkdirFile.delete()
                    try {
                        Os.symlink("gnumkdir", mkdirFile.absolutePath)
                    } catch (e: Exception) {
                        gnumkdir.copyTo(mkdirFile, overwrite = true)
                    }
                }
                coreutils.exists() -> {
                    mkdirFile.delete()
                    try {
                        Os.symlink("coreutils", mkdirFile.absolutePath)
                    } catch (e: Exception) {
                        coreutils.copyTo(mkdirFile, overwrite = true)
                    }
                }
            }
            mkdirFile.setReadable(true, false)
            mkdirFile.setExecutable(true, false)
            try {
                Os.chmod(mkdirFile.absolutePath, MODE_755)
            } catch (e: Exception) {}
        }

        AvsLogger.d(TAG, "Staged rootfs passed structure verification")
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
