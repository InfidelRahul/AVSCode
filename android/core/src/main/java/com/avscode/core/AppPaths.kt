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

    // VS Code CLI paths inside guest
    val guestVsCodeCliBin: String = "/usr/local/bin/code"
    val guestVsCodeDataDir: String = "/home/user/.vscode-cli"
    val hostVsCodeCliBin: File get() = File(rootfsDir, "usr/local/bin/code")
    val hostVsCodeDataDir: File get() = File(rootfsDir, "home/user/.vscode-cli")

    // Log files
    val serverLogFile: File get() = File(cacheDir, "vscode-server.log")
    val runtimeLogFile: File get() = File(cacheDir, "linux-runtime.log")

    // Guest Auth Bridge helper
    val guestAuthHelperScript: String = "/usr/local/bin/avscode-auth"
    val hostAuthHelperScript: File get() = File(rootfsDir, "usr/local/bin/avscode-auth")

    // Bootstrap script inside guest rootfs
    val guestBootstrapScript: String = "/usr/local/lib/avscode/bootstrap.sh"
    val hostBootstrapScript: File get() = File(rootfsDir, "usr/local/lib/avscode/bootstrap.sh")

    // Bootstrap marker inside guest rootfs
    val guestBootstrapMarker: String = "/var/lib/avscode/bootstrapped"
    val hostBootstrapMarker: File get() = File(rootfsDir, "var/lib/avscode/bootstrapped")

    // Guest /tmp directory inside the rootfs
    val hostGuestTmpDir: File get() = File(rootfsDir, "tmp")

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

