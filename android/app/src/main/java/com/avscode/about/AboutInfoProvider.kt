package com.avscode.about

import android.content.Context
import android.os.Build
import com.avscode.BuildConfig
import com.avscode.core.AppPaths
import com.avscode.vscode.VsCodeCliManager
import java.io.File

data class AboutInfo(
    val appVersion: String,
    val linuxDistro: String,
    val vsCodeVersion: String,
    val androidVersion: String,
    val sdkInt: Int,
    val deviceModel: String,
    val cpuArch: String,
    val kernelVersion: String,
    val projectsPath: String,
    val rootfsPath: String
)

class AboutInfoProvider(private val context: Context) {

    private val appPaths = AppPaths.getInstance(context)

    fun getAboutInfo(dynamicVsCodeVersion: String? = null): AboutInfo {
        val distro = resolveLinuxDistro()
        val vsCodeVer = dynamicVsCodeVersion ?: resolveVsCodeVersion()
        val device = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        val cpuArch = Build.SUPPORTED_ABIS.firstOrNull() ?: System.getProperty("os.arch") ?: "arm64-v8a"
        val kernel = System.getProperty("os.version") ?: "Linux"

        return AboutInfo(
            appVersion = "v${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})",
            linuxDistro = distro,
            vsCodeVersion = vsCodeVer,
            androidVersion = "Android ${Build.VERSION.RELEASE}",
            sdkInt = Build.VERSION.SDK_INT,
            deviceModel = device,
            cpuArch = cpuArch,
            kernelVersion = kernel,
            projectsPath = appPaths.guestProjectsPath,
            rootfsPath = appPaths.rootfsDir.absolutePath
        )
    }

    suspend fun resolveDynamicVsCodeVersion(cliManager: VsCodeCliManager): String {
        if (!cliManager.isInstalled()) {
            return "Not installed"
        }
        val queried = cliManager.getCliVersion()
        if (!queried.isNullOrBlank()) {
            // Cache queried version
            try {
                val versionFile = File(appPaths.rootfsDir, "var/lib/avscode/code-version")
                versionFile.parentFile?.mkdirs()
                versionFile.writeText(queried)
            } catch (ignored: Exception) {}
            return "$queried (ARM64)"
        }

        // Fallback to cached version if exists
        val cached = readCachedVersion()
        return cached ?: "Unavailable"
    }

    private fun resolveLinuxDistro(): String {
        val osReleaseFile = File(appPaths.rootfsDir, "etc/os-release")
        if (osReleaseFile.exists() && osReleaseFile.canRead()) {
            try {
                var prettyName = ""
                osReleaseFile.forEachLine { line ->
                    if (line.startsWith("PRETTY_NAME=")) {
                        prettyName = line.removePrefix("PRETTY_NAME=").trim('"', '\'')
                    }
                }
                if (prettyName.isNotEmpty()) {
                    return "$prettyName (ARM64)"
                }
            } catch (ignored: Exception) {
            }
        }
        return if (appPaths.rootfsInstallMarker.exists()) "Ubuntu 26.04 LTS (ARM64)" else "Not installed"
    }

    private fun resolveVsCodeVersion(): String {
        val cliBin = File(appPaths.rootfsDir, "usr/local/bin/code")
        if (!cliBin.exists()) {
            return "Not installed"
        }
        return readCachedVersion() ?: "Unavailable"
    }

    private fun readCachedVersion(): String? {
        val versionFile = File(appPaths.rootfsDir, "var/lib/avscode/code-version")
        if (versionFile.exists() && versionFile.canRead()) {
            val text = versionFile.readText().trim()
            if (text.isNotEmpty()) return "$text (ARM64)"
        }
        return null
    }
}
