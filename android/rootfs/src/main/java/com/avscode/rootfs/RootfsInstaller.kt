package com.avscode.rootfs

import android.content.Context
import com.avscode.core.AvsLogger
import com.avscode.core.Result
import com.avscode.core.runCatchingResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Rootfs installation manager.
 * 
 * Handles downloading, extracting, and verifying the Ubuntu rootfs.
 */
class RootfsInstaller(
    private val context: Context
) {
    
    companion object {
        private const val TAG = "RootfsInstaller"
        
        // Ubuntu Base ARM64 rootfs URL - Ubuntu 26.04 (Resolute)
        const val ROOTFS_URL = "https://cdimage.ubuntu.com/ubuntu-base/releases/resolute/release/ubuntu-base-26.04-base-arm64.tar.gz"
        
        // Installation directory
        const val ROOTFS_DIR = "ubuntu-rootfs"
        
        // Minimum required disk space in bytes (2GB)
        const val MIN_DISK_SPACE = 2L * 1024 * 1024 * 1024
        
        // Download buffer size
        const val BUFFER_SIZE = 8192
    }
    
    private val rootfsDir: File by lazy {
        File(context.filesDir, ROOTFS_DIR)
    }
    
    private val tempDownloadFile: File by lazy {
        File(context.cacheDir, "rootfs-download.tar.gz")
    }
    
    /**
     * Check if rootfs is properly installed.
     */
    fun isInstalled(): Boolean {
        return rootfsDir.exists() && 
               File(rootfsDir, "bin/bash").exists() &&
               File(rootfsDir, "etc/passwd").exists()
    }
    
    /**
     * Get the rootfs path.
     */
    fun getRootfsPath(): String {
        return rootfsDir.absolutePath
    }
    
    /**
     * Install the rootfs.
     */
    suspend fun install(progressCallback: ((Float) -> Unit)? = null): Result<Unit> = withContext(Dispatchers.IO) {
        AvsLogger.i(TAG, "Starting rootfs installation")
        
        runCatchingResult {
            // Check if already installed
            if (isInstalled()) {
                AvsLogger.w(TAG, "Rootfs already installed")
                return@runCatchingResult Result.Success(Unit)
            }
            
            // Check disk space
            checkDiskSpace()
            
            // Download rootfs
            downloadRootfs { progress ->
                progressCallback?.invoke(progress * 0.5f) // Download is 50% of total
            }
            
            // Extract rootfs
            extractRootfs { progress ->
                progressCallback?.invoke(0.5f + progress * 0.5f) // Extraction is remaining 50%
            }
            
            // Verify installation
            verifyInstallation()
            
            // Cleanup temp files
            cleanupTempFiles()
            
            AvsLogger.i(TAG, "Rootfs installation completed successfully")
        }
    }
    
    /**
     * Check available disk space.
     */
    private fun checkDiskSpace() {
        val statFs = android.os.StatFs(context.filesDir.absolutePath)
        val availableBytes = statFs.availableBytes.toLong()
        
        if (availableBytes < MIN_DISK_SPACE) {
            throw InsufficientDiskSpaceException(
                "Insufficient disk space. Required: ${MIN_DISK_SPACE / 1024 / 1024}MB, Available: ${availableBytes / 1024 / 1024}MB"
            )
        }
        
        AvsLogger.d(TAG, "Disk space OK: ${availableBytes / 1024 / 1024}MB available")
    }
    
    /**
     * Download the rootfs tarball.
     */
    private suspend fun downloadRootfs(progressCallback: ((Float) -> Unit)? = null) {
        AvsLogger.i(TAG, "Downloading rootfs from $ROOTFS_URL")
        
        withContext(Dispatchers.IO) {
            val url = URL(ROOTFS_URL)
            val connection = url.openConnection() as HttpURLConnection
            
            try {
                connection.connectTimeout = 30000
                connection.readTimeout = 30000
                connection.requestMethod = "GET"
                connection.doInput = true
                
                val contentLength = connection.contentLength.toLong()
                var downloadedBytes = 0L
                
                connection.inputStream.use { input ->
                    FileOutputStream(tempDownloadFile).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            
                            if (contentLength > 0) {
                                progressCallback?.invoke(downloadedBytes.toFloat() / contentLength.toFloat())
                            }
                        }
                    }
                }
                
                AvsLogger.i(TAG, "Download completed: ${downloadedBytes / 1024 / 1024}MB")
            } finally {
                connection.disconnect()
            }
        }
    }
    
    /**
     * Extract the rootfs tarball.
     */
    private suspend fun extractRootfs(progressCallback: ((Float) -> Unit)? = null) {
        AvsLogger.i(TAG, "Extracting rootfs")
        
        withContext(Dispatchers.IO) {
            // Create rootfs directory
            rootfsDir.mkdirs()
            
            // Use system tar command for extraction (more reliable than pure Kotlin implementation)
            val process = ProcessBuilder(
                "tar",
                "-xzf",
                tempDownloadFile.absolutePath,
                "-C",
                rootfsDir.absolutePath
            ).start()
            
            val exitCode = process.waitFor()
            
            if (exitCode != 0) {
                throw RuntimeException("Tar extraction failed with exit code: $exitCode")
            }
            
            progressCallback?.invoke(1.0f)
            AvsLogger.i(TAG, "Extraction completed")
        }
    }
    
    /**
     * Verify the installation.
     */
    private fun verifyInstallation() {
        AvsLogger.d(TAG, "Verifying installation")
        
        val requiredPaths = listOf(
            "bin/bash",
            "bin/sh",
            "etc/passwd",
            "usr/bin",
            "lib",
            "home"
        )
        
        for (path in requiredPaths) {
            val file = File(rootfsDir, path)
            if (!file.exists()) {
                throw VerificationException("Missing required path: $path")
            }
        }
        
        AvsLogger.d(TAG, "Verification passed")
    }
    
    /**
     * Cleanup temporary files.
     */
    private fun cleanupTempFiles() {
        tempDownloadFile.delete()
        AvsLogger.d(TAG, "Temporary files cleaned up")
    }
    
    /**
     * Uninstall the rootfs.
     */
    fun uninstall() {
        AvsLogger.i(TAG, "Uninstalling rootfs")
        rootfsDir.deleteRecursively()
        cleanupTempFiles()
    }
    
    /**
     * Custom exception for insufficient disk space.
     */
    class InsufficientDiskSpaceException(message: String) : Exception(message)
    
    /**
     * Custom exception for verification failures.
     */
    class VerificationException(message: String) : Exception(message)
}
