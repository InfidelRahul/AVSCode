package com.avscode.core

import android.content.Context
import java.io.File

/**
 * Centralized paths for AVscode filesystem locations.
 * Ensures all modules (installer, runtime, server, diagnostics) agree on exact paths.
 */
class AppPaths(private val context: Context) {

    val filesDir: File get() = context.filesDir
    val cacheDir: File get() = context.cacheDir

    // Rootfs directory name & file
    val rootfsDirName: String = "ubuntu-rootfs"
    val rootfsDir: File get() = File(filesDir, rootfsDirName)
    val rootfsStagingDir: File get() = File(filesDir, "ubuntu-rootfs-staging")
    val rootfsInstallMarker: File get() = File(rootfsDir, ".installed")

    // PRoot temporary directory on Android host (outside guest chroot)
    val prootTmpDir: File get() = File(cacheDir, "proot-tmp").apply { mkdirs() }

    // Native library directory containing libproot.so, libproot_loader.so, etc.
    val nativeLibDir: File get() = File(context.applicationInfo.nativeLibraryDir)

    // Dedicated executable directory fallback if nativeLibraryDir is not executable
    val nativeBinDir: File get() = File(filesDir, "native-bin").apply { mkdirs() }

    // Projects directory inside guest rootfs
    val guestProjectsPath: String = "/home/user/projects"
    val hostProjectsDir: File get() = File(rootfsDir, "home/user/projects")

    // code-server paths inside guest chroot
    val guestCodeServerDir: String = "/opt/code-server"
    val guestCodeServerBin: String = "/opt/code-server/bin/code-server"
    val guestCodeServerDataDir: String = "/home/user/.local/share/code-server"
    val hostCodeServerDir: File get() = File(rootfsDir, "opt/code-server")
    val hostCodeServerDataDir: File get() = File(rootfsDir, "home/user/.local/share/code-server")

    // Log files
    val serverLogFile: File get() = File(hostCodeServerDataDir, "code-server.log")
    val runtimeLogFile: File get() = File(cacheDir, "linux-runtime.log")

    // Bootstrap marker inside guest rootfs
    val hostBootstrapMarker: File get() = File(rootfsDir, "var/lib/avscode-bootstrapped")

    companion object {
        @Volatile
        private var instance: AppPaths? = null

        fun getInstance(context: Context): AppPaths {
            return instance ?: synchronized(this) {
                instance ?: AppPaths(context.applicationContext).also { instance = it }
            }
        }
    }
}

