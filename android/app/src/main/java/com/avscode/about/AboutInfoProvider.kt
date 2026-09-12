package com.avscode.about

import android.content.Context
import android.os.Build
import com.avscode.BuildConfig
import com.avscode.core.AppPaths
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

    fun getAboutInfo(): AboutInfo {
        val distro = resolveLinuxDistro()
        val vsCodeVer = resolveVsCodeVersion()
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
        return "Ubuntu 26.04 LTS (ARM64)"
    }

    private fun resolveVsCodeVersion(): String {
        // VS Code CLI default or version file if cached
        val versionFile = File(appPaths.rootfsDir, "var/lib/avscode/code-version")
        if (versionFile.exists() && versionFile.canRead()) {
            val text = versionFile.readText().trim()
            if (text.isNotEmpty()) return text
        }
        return "VS Code CLI 1.97.2 (ARM64)"
    }
}

