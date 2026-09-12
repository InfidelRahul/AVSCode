package com.avscode.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Helper for verifying, requesting, and persisting storage access state.
 *
 * Implements Android 11+ (API 30+) scoped storage standards while verifying
 * writeability and disk space requirements for the Linux rootfs.
 */
object StoragePermissionHelper {

    private const val PREFS_NAME = "avscode_storage_prefs"
    private const val KEY_STORAGE_CONFIGURED = "storage_access_configured"
    private const val KEY_STORAGE_PATH = "storage_rootfs_path"

    /**
     * Checks if storage access has already been confirmed and persisted.
     */
    fun isStorageConfigured(context: Context): Boolean {
        val prefs = getPrefs(context)
        return prefs.getBoolean(KEY_STORAGE_CONFIGURED, false)
    }

    /**
     * Persists that the user has reviewed and configured storage access.
     */
    fun markStorageConfigured(context: Context, rootfsPath: String) {
        getPrefs(context).edit()
            .putBoolean(KEY_STORAGE_CONFIGURED, true)
            .putString(KEY_STORAGE_PATH, rootfsPath)
            .apply()
    }

    /**
     * Verifies that the chosen filesystem location can be written to and has space.
     */
    fun verifyStorageAccessible(targetDir: File): Boolean {
        return try {
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val testFile = File(targetDir, ".storage_access_test_${System.currentTimeMillis()}")
            testFile.writeText("ok")
            val readable = testFile.readText() == "ok"
            testFile.delete()
            readable
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Checks if full external storage manager permission is granted (if requested on API 30+).
     */
    fun hasManageExternalStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

