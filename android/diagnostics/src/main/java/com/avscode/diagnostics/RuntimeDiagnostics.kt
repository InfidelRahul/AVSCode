package com.avscode.diagnostics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import com.avscode.core.AppPaths
import com.avscode.core.AvsLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Comprehensive diagnostics for AVscode runtime.
 * Collects real dynamic information about all layers: Android, PRoot, Linux, VS Code Server, Network.
 */
@android.annotation.SuppressLint("MissingPermission", "SdCardPath")
object RuntimeDiagnostics {

    private const val TAG = "AVscode.Diagnostics"

    suspend fun collectFullDiagnostics(context: Context? = null): DiagnosticsReport = withContext(Dispatchers.IO) {
        AvsLogger.d(TAG, "Collecting full diagnostics report...")

        val paths = context?.let { AppPaths.getInstance(it) }

        DiagnosticsReport(
            timestamp = System.currentTimeMillis(),
            androidInfo = collectAndroidInfo(context),
            storageInfo = collectStorageInfo(context, paths),
            rootfsInfo = collectRootfsInfo(paths),
            pruntimeInfo = collectPRootInfo(paths),
            linuxInfo = collectLinuxInfo(paths),
            vscodeInfo = collectVsCodeInfo(paths),
            networkInfo = collectNetworkInfo(context),
            logEntries = AvsLogger.logs.value.takeLast(100)
        )
    }

    private fun collectAndroidInfo(context: Context? = null): AndroidInfo {
        val isDebug = context?.let { (it.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 } ?: false
        return AndroidInfo(
            sdkVersion = Build.VERSION.SDK_INT,
            androidVersion = Build.VERSION.RELEASE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            abi = Build.SUPPORTED_ABIS.joinToString(", "),
            supportedAbis = Build.SUPPORTED_64_BIT_ABIS?.joinToString(", ") ?: "",
            isDebuggable = isDebug,
            memoryClass = Runtime.getRuntime().maxMemory() / (1024 * 1024),
            availableProcessors = Runtime.getRuntime().availableProcessors()
        )
    }

    private fun collectStorageInfo(context: Context?, paths: AppPaths?): StorageInfo {
        val externalDir = Environment.getExternalStorageDirectory()
        val internalDir = context?.filesDir ?: File("/data/data/com.avscode/files")
        val appFilesDir = internalDir
        val rootfsDir = paths?.rootfsDir ?: File(appFilesDir, "ubuntu-rootfs")
        val vsCodeCliBinary = paths?.hostVsCodeCliBin ?: File(rootfsDir, "usr/local/bin/code")

        return StorageInfo(
            externalStorageTotal = externalDir.totalSpace,
            externalStorageFree = externalDir.freeSpace,
            externalStorageAvailable = externalDir.canWrite(),
            internalStorageTotal = internalDir.totalSpace,
            internalStorageFree = internalDir.freeSpace,
            appFilesDirExists = appFilesDir.exists(),
            appFilesDirCanWrite = appFilesDir.canWrite(),
            rootfsInstalled = paths?.rootfsInstallMarker?.exists() ?: File(rootfsDir, ".installed").exists(),
            vsCodeCliInstalled = vsCodeCliBinary.exists()
        )
    }

    private fun collectRootfsInfo(paths: AppPaths?): RootfsInfo {
        val rootfsDir = paths?.rootfsDir ?: File("/data/data/com.avscode/files/ubuntu-rootfs")

        if (!rootfsDir.exists()) {
            return RootfsInfo(
                installed = false,
                path = rootfsDir.absolutePath,
                exists = false
            )
        }

        val binBash = File(rootfsDir, "bin/bash").takeIf { it.exists() } ?: File(rootfsDir, "usr/bin/bash")
        val binSh = File(rootfsDir, "bin/sh").takeIf { it.exists() } ?: File(rootfsDir, "usr/bin/sh")
        val etcPasswd = File(rootfsDir, "etc/passwd")
        val homeDir = File(rootfsDir, "home/user")
        val isInstalled = paths?.rootfsInstallMarker?.exists() ?: File(rootfsDir, ".installed").exists()

        return RootfsInfo(
            installed = isInstalled,
            path = rootfsDir.absolutePath,
            exists = rootfsDir.exists(),
            canRead = rootfsDir.canRead(),
            canWrite = rootfsDir.canWrite(),
            hasBinBash = binBash.exists(),
            hasBinSh = binSh.exists(),
            hasEtcPasswd = etcPasswd.exists(),
            hasHomeDir = homeDir.exists(),
            totalSize = calculateDirSize(rootfsDir)
        )
    }

    private fun collectPRootInfo(paths: AppPaths?): PRootInfo {
        var nativeLibraryLoaded = false
        try {
            System.loadLibrary("avscodespawn")
            nativeLibraryLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            try {
                System.loadLibrary("linuxdroidspawn")
                nativeLibraryLoaded = true
            } catch (e2: UnsatisfiedLinkError) {
                nativeLibraryLoaded = false
            }
        }

        val prootBin = paths?.let { File(it.nativeLibDir, "libproot.so") }
        val prootExists = prootBin?.exists() == true

        return PRootInfo(
            nativeLibraryLoaded = nativeLibraryLoaded,
            libraryPath = if (prootExists) prootBin.absolutePath else "libproot.so (exists=$prootExists)"
        )
    }

    private fun collectLinuxInfo(paths: AppPaths?): LinuxInfo {
        val rootfsDir = paths?.rootfsDir ?: File("/data/data/com.avscode/files/ubuntu-rootfs")
        val projectsDir = File(rootfsDir, "home/user/projects")

        return LinuxInfo(
            rootfsPath = rootfsDir.absolutePath,
            projectsDirExists = projectsDir.exists(),
            projectsDirCanWrite = projectsDir.canWrite(),
            homeUserExists = File(rootfsDir, "home/user").exists()
        )
    }

    private fun collectVsCodeInfo(paths: AppPaths?): VsCodeInfo {
        val rootfsDir = paths?.rootfsDir ?: File("/data/data/com.avscode/files/ubuntu-rootfs")
        val cliBinary = paths?.hostVsCodeCliBin ?: File(rootfsDir, "usr/local/bin/code")
        val userDataDir = paths?.hostVsCodeDataDir ?: File(rootfsDir, "home/user/.vscode-cli")

        return VsCodeInfo(
            installed = cliBinary.exists(),
            binaryExists = cliBinary.exists(),
            binaryCanExecute = cliBinary.canExecute(),
            userDataDirExists = userDataDir.exists(),
            installPath = cliBinary.absolutePath,
            version = "Microsoft VS Code CLI"
        )
    }

    private fun collectNetworkInfo(context: Context?): NetworkInfo {
        var hasConn = false
        var isWifi = false

        if (context != null) {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val activeNetwork = cm?.activeNetwork
                val capabilities = cm?.getNetworkCapabilities(activeNetwork)
                if (capabilities != null) {
                    hasConn = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                }
            } catch (e: Exception) {
                AvsLogger.w(TAG, "Failed to query network capabilities: ${e.message}")
            }
        }

        return NetworkInfo(
            hasInternetPermission = true,
            isWifiEnabled = isWifi,
            hasConnectivity = hasConn
        )
    }

    private fun calculateDirSize(dir: File): Long {
        return try {
            dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } catch (e: Exception) {
            -1L
        }
    }

    fun exportToText(report: DiagnosticsReport): String {
        val sb = StringBuilder()

        sb.appendLine("=== AVSCode Diagnostics Report ===")
        sb.appendLine("Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(report.timestamp))}")
        sb.appendLine()

        sb.appendLine("--- Android Info ---")
        sb.appendLine("SDK Version: ${report.androidInfo.sdkVersion}")
        sb.appendLine("Android Version: ${report.androidInfo.androidVersion}")
        sb.appendLine("Device: ${report.androidInfo.manufacturer} ${report.androidInfo.model} (${report.androidInfo.device})")
        sb.appendLine("ABI: ${report.androidInfo.abi}")
        sb.appendLine("Supported 64-bit ABIs: ${report.androidInfo.supportedAbis}")
        sb.appendLine("Debuggable: ${report.androidInfo.isDebuggable}")
        sb.appendLine("Memory Class: ${report.androidInfo.memoryClass} MB")
        sb.appendLine("CPU Cores: ${report.androidInfo.availableProcessors}")
        sb.appendLine()

        sb.appendLine("--- Storage Info ---")
        sb.appendLine("External Storage Total: ${formatBytes(report.storageInfo.externalStorageTotal)}")
        sb.appendLine("External Storage Free: ${formatBytes(report.storageInfo.externalStorageFree)}")
        sb.appendLine("External Storage Writable: ${report.storageInfo.externalStorageAvailable}")
        sb.appendLine("Internal Storage Total: ${formatBytes(report.storageInfo.internalStorageTotal)}")
        sb.appendLine("Internal Storage Free: ${formatBytes(report.storageInfo.internalStorageFree)}")
        sb.appendLine("App Files Dir Exists: ${report.storageInfo.appFilesDirExists}")
        sb.appendLine("App Files Dir Writable: ${report.storageInfo.appFilesDirCanWrite}")
        sb.appendLine("Rootfs Installed: ${report.storageInfo.rootfsInstalled}")
        sb.appendLine("VS Code CLI Installed: ${report.storageInfo.vsCodeCliInstalled}")
        sb.appendLine()

        sb.appendLine("--- Rootfs Info ---")
        sb.appendLine("Installed: ${report.rootfsInfo.installed}")
        sb.appendLine("Path: ${report.rootfsInfo.path}")
        sb.appendLine("Exists: ${report.rootfsInfo.exists}")
        sb.appendLine("Readable: ${report.rootfsInfo.canRead}")
        sb.appendLine("Writable: ${report.rootfsInfo.canWrite}")
        sb.appendLine("Has /bin/bash: ${report.rootfsInfo.hasBinBash}")
        sb.appendLine("Has /bin/sh: ${report.rootfsInfo.hasBinSh}")
        sb.appendLine("Has /etc/passwd: ${report.rootfsInfo.hasEtcPasswd}")
        sb.appendLine("Has /home/user: ${report.rootfsInfo.hasHomeDir}")
        if (report.rootfsInfo.totalSize > 0) {
            sb.appendLine("Total Size: ${formatBytes(report.rootfsInfo.totalSize)}")
        }
        sb.appendLine()

        sb.appendLine("--- PRoot Info ---")
        sb.appendLine("Native Library Loaded: ${report.pruntimeInfo.nativeLibraryLoaded}")
        sb.appendLine("Binary Path: ${report.pruntimeInfo.libraryPath ?: "N/A"}")
        sb.appendLine()

        sb.appendLine("--- Linux Info ---")
        sb.appendLine("Rootfs Path: ${report.linuxInfo.rootfsPath}")
        sb.appendLine("Projects Dir Exists: ${report.linuxInfo.projectsDirExists}")
        sb.appendLine("Projects Dir Writable: ${report.linuxInfo.projectsDirCanWrite}")
        sb.appendLine("Home User Exists: ${report.linuxInfo.homeUserExists}")
        sb.appendLine()

        sb.appendLine("--- VS Code Server Info ---")
        sb.appendLine("Installed: ${report.vscodeInfo.installed}")
        sb.appendLine("Binary Exists: ${report.vscodeInfo.binaryExists}")
        sb.appendLine("Binary Executable: ${report.vscodeInfo.binaryCanExecute}")
        sb.appendLine("User Data Dir Exists: ${report.vscodeInfo.userDataDirExists}")
        sb.appendLine("Install Path: ${report.vscodeInfo.installPath}")
        sb.appendLine("Version: ${report.vscodeInfo.version ?: "Unknown"}")
        sb.appendLine()

        sb.appendLine("--- Network Info ---")
        sb.appendLine("Internet Permission: ${report.networkInfo.hasInternetPermission}")
        sb.appendLine("WiFi Enabled: ${report.networkInfo.isWifiEnabled}")
        sb.appendLine("Has Connectivity: ${report.networkInfo.hasConnectivity}")
        sb.appendLine()

        sb.appendLine("--- Recent Logs (last 20) ---")
        report.logEntries.takeLast(20).forEach { entry ->
            val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date(entry.timestamp))
            sb.appendLine("[$time] ${entry.level}: ${entry.tag}: ${entry.message}")
            val err = entry.throwable
            if (err != null) {
                sb.appendLine("  ${err.javaClass.simpleName}: ${err.message}")
            }
        }

        return sb.toString()
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }
}

data class DiagnosticsReport(
    val timestamp: Long,
    val androidInfo: AndroidInfo,
    val storageInfo: StorageInfo,
    val rootfsInfo: RootfsInfo,
    val pruntimeInfo: PRootInfo,
    val linuxInfo: LinuxInfo,
    val vscodeInfo: VsCodeInfo,
    val networkInfo: NetworkInfo,
    val logEntries: List<AvsLogger.LogEntry>
)

data class AndroidInfo(
    val sdkVersion: Int,
    val androidVersion: String,
    val manufacturer: String,
    val model: String,
    val device: String,
    val abi: String,
    val supportedAbis: String,
    val isDebuggable: Boolean,
    val memoryClass: Long,
    val availableProcessors: Int
)

data class StorageInfo(
    val externalStorageTotal: Long,
    val externalStorageFree: Long,
    val externalStorageAvailable: Boolean,
    val internalStorageTotal: Long,
    val internalStorageFree: Long,
    val appFilesDirExists: Boolean,
    val appFilesDirCanWrite: Boolean,
    val rootfsInstalled: Boolean,
    val vsCodeCliInstalled: Boolean
) {
    val codeServerInstalled: Boolean get() = vsCodeCliInstalled
}

data class RootfsInfo(
    val installed: Boolean,
    val path: String,
    val exists: Boolean,
    val canRead: Boolean = false,
    val canWrite: Boolean = false,
    val hasBinBash: Boolean = false,
    val hasBinSh: Boolean = false,
    val hasEtcPasswd: Boolean = false,
    val hasHomeDir: Boolean = false,
    val totalSize: Long = 0L
)

data class PRootInfo(
    val nativeLibraryLoaded: Boolean,
    val libraryPath: String?
)

data class LinuxInfo(
    val rootfsPath: String,
    val projectsDirExists: Boolean,
    val projectsDirCanWrite: Boolean,
    val homeUserExists: Boolean
)

data class VsCodeInfo(
    val installed: Boolean,
    val binaryExists: Boolean,
    val binaryCanExecute: Boolean,
    val userDataDirExists: Boolean,
    val installPath: String,
    val version: String?
)

data class NetworkInfo(
    val hasInternetPermission: Boolean,
    val isWifiEnabled: Boolean,
    val hasConnectivity: Boolean
)
