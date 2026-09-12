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
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

/**
 * Robust Ubuntu Rootfs Installer.
 *
 * Android host responsibility:
 * 1. Download Ubuntu 26.04 Base ARM64 archive.
 * 2. Extract filesystem using hardened TarArchiveInputStream without turning tar metadata (e.g. PAX headers) into filesystem objects.
 * 3. Configure guest filesystem (mountpoints, DNS, hosts, APT sandbox, policy-rc.d, user accounts).
 * 4. Verify Linux directory structure, essential binaries, and guest symlinks.
 * 5. Inject guest bootstrap script /usr/local/lib/avscode/bootstrap.sh.
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

        /**
         * Resolves a guest path strictly within [rootfsDir], following symlinks up to [maxHops].
         * Guest absolute symlinks are interpreted relative to [rootfsDir].
         */
        fun resolveGuestSymlink(rootfsDir: File, guestPath: String, maxHops: Int = 16): File? {
            val rootfsCanonical = rootfsDir.canonicalFile
            var current = File(rootfsCanonical, guestPath.removePrefix("/"))
            var hops = 0

            while (hops < maxHops) {
                val currentPath: Path = current.toPath()
                if (!Files.isSymbolicLink(currentPath)) {
                    if (!current.exists()) return null
                    val targetCanonical = current.canonicalFile
                    if (!targetCanonical.path.startsWith(rootfsCanonical.path)) {
                        AvsLogger.w(TAG, "Path traversal out of rootfs detected: ${current.path}")
                        return null
                    }
                    return current
                }

                val rawTarget = try {
                    Files.readSymbolicLink(currentPath).toString()
                } catch (e: Exception) {
                    return null
                }

                hops++
                current = if (rawTarget.startsWith("/")) {
                    File(rootfsCanonical, rawTarget.removePrefix("/"))
                } else {
                    File(current.parentFile ?: rootfsCanonical, rawTarget)
                }
            }

            AvsLogger.w(TAG, "Symlink loop detected for $guestPath (exceeded $maxHops hops)")
            return null
        }
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
        val hasGroup = File(rootfsDir, "etc/group").exists()
        val hasOsRelease = File(rootfsDir, "etc/os-release").exists()
        val hasMkdir = File(rootfsDir, "usr/bin/mkdir").exists()
        val hasBin = File(rootfsDir, "bin").exists()
        val hasUsr = File(rootfsDir, "usr").exists()
        val hasEtc = File(rootfsDir, "etc").exists()
        val hasHome = File(rootfsDir, "home").exists()
        val hasTmp = File(rootfsDir, "tmp").exists()
        val noPaxHeaders = !File(rootfsDir, "etc/dpkg/dpkg.cfg.d/PaxHeaders").exists()

        return marker.exists() && hasBash && hasSh && hasPasswd && hasGroup && hasOsRelease &&
                hasMkdir && hasBin && hasUsr && hasEtc && hasHome && hasTmp && noPaxHeaders
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
        AvsLogger.i(TAG, "Extracting ${tarGzFile.name} to ${destDir.absolutePath} using hardened TarArchiveInputStream")
        extractWithCommonsCompress(tarGzFile, destDir, progressCallback)
    }

    private data class DeferredHardLink(
        val targetFile: File,
        val sourceFile: File,
        val cleanLink: String,
        val mode: Int
    )

    /**
     * Hardened Tar.gz extractor using Apache Commons Compress TarArchiveInputStream.
     *
     * Handles:
     * - POSIX PAX extended headers without creating rogue PaxHeaders directories or filesystem entries
     * - GNU LongLink and LongName records
     * - USTAR prefixes and standard directory entries
     * - Deferred hardlinks for entries whose source files appear later in the stream
     * - Traversability (0755) for all created parent directories
     * - Preservation of execute bits and POSIX mode flags
     */
    private fun extractWithCommonsCompress(
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

        val destCanonicalPath = destDir.canonicalPath
        val deferredHardLinks = mutableListOf<DeferredHardLink>()
        val buffer = ByteArray(BUFFER_SIZE)
        var lastProgressUpdate = 0L
        var entryCount = 0

        BufferedInputStream(countingStream, BUFFER_SIZE).use { bufferedInput ->
            GzipCompressorInputStream(bufferedInput).use { gzipStream ->
                TarArchiveInputStream(gzipStream).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextEntry
                    while (entry != null) {
                        entryCount++

                        // Explicitly discard any PAX header metadata entries
                        if (entry.isPaxHeader || entry.isGlobalPaxHeader) {
                            entry = tarIn.nextEntry
                            continue
                        }

                        val rawName = entry.name.removePrefix("./").removePrefix("/")

                        // Discard rogue PAX header entries that might otherwise become directory objects
                        if (rawName.isEmpty() || rawName.contains("PaxHeaders")) {
                            entry = tarIn.nextEntry
                            continue
                        }

                        val targetFile = File(destDir, rawName)
                        val targetCanonical = targetFile.canonicalPath

                        // Path traversal defense
                        if (!targetCanonical.startsWith(destCanonicalPath)) {
                            throw SecurityException("Path traversal attempt in archive entry: ${entry.name}")
                        }

                        when {
                            entry.isDirectory -> {
                                targetFile.mkdirs()
                                ensureDirTraversable(targetFile)
                                try {
                                    Os.chmod(targetFile.absolutePath, if (entry.mode != 0) (entry.mode or MODE_755) else MODE_755)
                                } catch (e: Exception) {}
                            }
                            entry.isSymbolicLink -> {
                                targetFile.parentFile?.let { ensureDirTraversable(it) }
                                targetFile.delete()
                                val linkTarget = entry.linkName
                                try {
                                    Os.symlink(linkTarget, targetFile.absolutePath)
                                } catch (e: Exception) {
                                    AvsLogger.w(TAG, "Symlink failed for $rawName -> $linkTarget: ${e.message}")
                                }
                            }
                            entry.isLink -> {
                                targetFile.parentFile?.let { ensureDirTraversable(it) }
                                targetFile.delete()
                                val cleanLink = entry.linkName.removePrefix("/").removePrefix("./")
                                val sourceFile = File(destDir, cleanLink)
                                if (sourceFile.exists()) {
                                    createHardLinkOrFallback(targetFile, sourceFile, entry.mode)
                                } else {
                                    deferredHardLinks.add(DeferredHardLink(targetFile, sourceFile, cleanLink, entry.mode))
                                }
                            }
                            else -> {
                                targetFile.parentFile?.let { ensureDirTraversable(it) }
                                targetFile.delete()
                                FileOutputStream(targetFile).use { out ->
                                    var len: Int
                                    while (tarIn.read(buffer).also { len = it } != -1) {
                                        out.write(buffer, 0, len)
                                    }
                                }
                                applyFilePermissions(targetFile, rawName, entry.mode)
                            }
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 250) {
                            lastProgressUpdate = now
                            if (totalBytes > 0) {
                                val frac = (countingStream.bytesRead.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                progressCallback?.invoke(frac)
                            }
                        }

                        entry = tarIn.nextEntry
                    }
                }
            }
        }

        // Resolve deferred hard links whose source files were unpacked later in the stream
        for (deferred in deferredHardLinks) {
            try {
                if (deferred.sourceFile.exists()) {
                    deferred.targetFile.delete()
                    createHardLinkOrFallback(deferred.targetFile, deferred.sourceFile, deferred.mode)
                } else {
                    deferred.targetFile.delete()
                    Os.symlink(deferred.cleanLink, deferred.targetFile.absolutePath)
                }
            } catch (e: Exception) {
                AvsLogger.w(TAG, "Deferred hardlink failed for ${deferred.targetFile.name} -> ${deferred.cleanLink}: ${e.message}")
            }
        }

        // Validate that no PaxHeaders directory was created under etc/dpkg/dpkg.cfg.d
        val roguePax = File(destDir, "etc/dpkg/dpkg.cfg.d/PaxHeaders")
        if (roguePax.exists()) {
            roguePax.deleteRecursively()
        }

        progressCallback?.invoke(1.0f)
        AvsLogger.i(TAG, "Rootfs extraction finished with POSIX modes and permissions applied ($entryCount entries)")
    }

    private fun createHardLinkOrFallback(targetFile: File, sourceFile: File, mode: Int) {
        var linked = false
        try {
            Os.link(sourceFile.absolutePath, targetFile.absolutePath)
            linked = true
        } catch (e: Exception) {
            // Os.link failed across mounts
        }

        if (!linked) {
            try {
                sourceFile.copyTo(targetFile, overwrite = true)
                applyFilePermissions(targetFile, targetFile.name, mode)
            } catch (e: Exception) {
                try {
                    Os.symlink(sourceFile.name, targetFile.absolutePath)
                } catch (e2: Exception) {
                    AvsLogger.w(TAG, "Hard link fallback failed for ${targetFile.name}: ${e2.message}")
                }
            }
        }
    }

    private fun applyFilePermissions(targetFile: File, entryName: String, mode: Int) {
        targetFile.setReadable(true, false)
        val isExec = (mode and MODE_EXEC_BITS != 0) ||
                entryName.startsWith("bin/") ||
                entryName.startsWith("usr/bin/") ||
                entryName.startsWith("sbin/") ||
                entryName.startsWith("usr/sbin/") ||
                entryName.contains("/bin/") ||
                entryName.contains("/sbin/") ||
                entryName.endsWith(".sh")

        if (isExec) {
            targetFile.setExecutable(true, false)
        }

        try {
            val targetMode = if (mode != 0) mode else if (isExec) MODE_755 else MODE_644
            Os.chmod(targetFile.absolutePath, targetMode)
        } catch (e: Exception) {}
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

    /**
     * Configure essential rootfs files so networking, APT, users, and guest bootstrap work seamlessly.
     * Adapted from LinuxDroid's working rootfs deployment pattern without GUI dependencies.
     */
    private fun configureGuestEnvironment(rootfsDir: File) {
        AvsLogger.i(TAG, "Configuring guest environment (mountpoints, DNS, hosts, APT, bootstrap)...")

        // 1. Create essential PRoot guest directories
        val guestDirs = listOf(
            "dev",
            "dev/pts",
            "dev/shm",
            "proc",
            "sys",
            "tmp",
            "run",
            "home/user",
            "home/user/projects",
            "root",
            "var/lib/avscode",
            "usr/local/lib/avscode",
            "etc/avscode"
        )
        for (rel in guestDirs) {
            val d = File(rootfsDir, rel)
            d.mkdirs()
            ensureDirTraversable(d)
        }

        // Set /tmp permissions to 1777
        val tmpDir = File(rootfsDir, "tmp")
        tmpDir.setWritable(true, false)
        try {
            Os.chmod(tmpDir.absolutePath, MODE_1777)
        } catch (e: Exception) {}

        // 2. DNS configuration (/etc/resolv.conf)
        // Ensure no dangling symlinks (e.g. systemd stub) remain
        val resolvConf = File(rootfsDir, "etc/resolv.conf")
        resolvConf.parentFile?.let { ensureDirTraversable(it) }
        resolvConf.delete()
        resolvConf.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\nnameserver 8.8.4.4\n")
        resolvConf.setReadable(true, false)

        // 3. Hosts & Hostname configuration
        val hosts = File(rootfsDir, "etc/hosts")
        hosts.delete()
        hosts.writeText("127.0.0.1 localhost avscode\n::1 localhost ip6-localhost ip6-loopback\n")
        hosts.setReadable(true, false)

        val hostname = File(rootfsDir, "etc/hostname")
        hostname.delete()
        hostname.writeText("avscode\n")
        hostname.setReadable(true, false)

        // 4. Session environment (/etc/environment)
        val envFile = File(rootfsDir, "etc/environment")
        envFile.delete()
        envFile.writeText("PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"\nLANG=\"C.UTF-8\"\nSHELL=\"/bin/bash\"\n")
        envFile.setReadable(true, false)

        // 5. APT configuration & daemon policy
        // Prevent service auto-start in PRoot sandbox during apt package installs
        val policyFile = File(rootfsDir, "usr/sbin/policy-rc.d")
        policyFile.parentFile?.let { ensureDirTraversable(it) }
        policyFile.delete()
        policyFile.writeText("#!/bin/sh\nexit 101\n")
        policyFile.setReadable(true, false)
        policyFile.setExecutable(true, false)
        try {
            Os.chmod(policyFile.absolutePath, MODE_755)
        } catch (e: Exception) {}

        // APT sandbox and retry configs
        val aptConfigDir = File(rootfsDir, "etc/apt/apt.conf.d")
        ensureDirTraversable(aptConfigDir)
        File(aptConfigDir, "99avscode").writeText(
            """
            APT::Sandbox::User "root";
            Acquire::Languages "none";
            Acquire::Retries "3";
            Dpkg::Options {
               "--force-confdef";
               "--force-confold";
            };
            """.trimIndent() + "\n"
        )

        // Remove any stale excludes or locks
        File(rootfsDir, "etc/dpkg/dpkg.cfg.d/excludes").delete()

        // 6. User accounts (/etc/passwd and /etc/group)
        val passwdFile = File(rootfsDir, "etc/passwd")
        if (passwdFile.exists()) {
            val content = passwdFile.readText()
            if (!content.contains("user:")) {
                passwdFile.appendText("user:x:1000:1000:User:/home/user:/bin/bash\n")
            }
        }
        val groupFile = File(rootfsDir, "etc/group")
        if (groupFile.exists()) {
            val content = groupFile.readText()
            if (!content.contains("user:")) {
                groupFile.appendText("user:x:1000:\n")
            }
        }

        val userHome = File(rootfsDir, "home/user")
        ensureDirTraversable(userHome)
        val userProjects = File(userHome, "projects")
        ensureDirTraversable(userProjects)

        // User profile
        val userProfile = File(userHome, ".profile")
        if (!userProfile.exists()) {
            userProfile.writeText(
                """
                export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/opt/code-server/bin
                export SHELL=/bin/bash
                export LANG=C.UTF-8
                """.trimIndent() + "\n"
            )
            userProfile.setReadable(true, false)
        }

        // Root profile
        val rootProfile = File(rootfsDir, "root/.profile")
        rootProfile.parentFile?.let { ensureDirTraversable(it) }
        if (!rootProfile.exists()) {
            rootProfile.writeText("export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n")
            rootProfile.setReadable(true, false)
        }

        // 7. Deploy guest bootstrap script
        deployGuestBootstrapScript(rootfsDir)

        AvsLogger.d(TAG, "Guest environment configured successfully")
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
            |Dpkg::Options {
            |   "--force-confdef";
            |   "--force-confold";
            |};
            |EOF
            |
            |# Ensure policy-rc.d prevents service startups in PRoot
            |cat <<'EOF' > /usr/sbin/policy-rc.d
            |#!/bin/sh
            |exit 101
            |EOF
            |chmod +x /usr/sbin/policy-rc.d
            |
            |if [ ! -s /etc/resolv.conf ]; then
            |    echo "nameserver 1.1.1.1" > /etc/resolv.conf
            |    echo "nameserver 8.8.8.8" >> /etc/resolv.conf
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

    /**
     * Verifies structural integrity, mandatory binaries, and guest symlinks.
     * Fails explicitly if any PaxHeaders metadata directory exists in /etc/dpkg/dpkg.cfg.d.
     */
    private fun verifyStagedRootfs(stagingDir: File) {
        // 1. Validate that no PaxHeaders directory exists in dpkg config
        val paxHeadersDir = File(stagingDir, "etc/dpkg/dpkg.cfg.d/PaxHeaders")
        if (paxHeadersDir.exists()) {
            throw VerificationException("Rootfs extraction produced invalid PaxHeaders directory in ${paxHeadersDir.path}")
        }

        // 2. Structural Linux Directories
        val requiredDirs = listOf("bin", "usr", "etc", "home", "tmp", "dev", "proc", "sys", "var", "run")
        for (dir in requiredDirs) {
            val d = File(stagingDir, dir)
            if (!d.exists() || !d.isDirectory) {
                throw VerificationException("Rootfs verification failed: missing directory /$dir")
            }
        }

        // 3. Essential System Configuration Files
        val requiredFiles = listOf(
            "etc/passwd",
            "etc/group",
            "etc/os-release"
        )
        for (rel in requiredFiles) {
            val f = File(stagingDir, rel)
            if (!f.exists()) {
                throw VerificationException("Rootfs verification failed: missing $rel")
            }
        }

        // 4. Mandatory Shell Executables
        val bashResolved = resolveGuestSymlink(stagingDir, "/bin/bash") ?: resolveGuestSymlink(stagingDir, "/usr/bin/bash")
        val shResolved = resolveGuestSymlink(stagingDir, "/bin/sh") ?: resolveGuestSymlink(stagingDir, "/usr/bin/sh")
        if (bashResolved == null && shResolved == null) {
            throw VerificationException("Rootfs verification failed: neither /bin/bash nor /bin/sh resolved to a valid executable")
        }

        // 5. Verify and repair usr/bin/mkdir if needed
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

        // 6. Verify guest bootstrap script is installed and executable
        val bootstrapScript = File(stagingDir, "usr/local/lib/avscode/bootstrap.sh")
        if (!bootstrapScript.exists() || !bootstrapScript.canExecute()) {
            throw VerificationException("Rootfs verification failed: missing or non-executable /usr/local/lib/avscode/bootstrap.sh")
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
