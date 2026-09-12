package com.avscode.ports

import com.avscode.core.AvsLogger
import java.io.File

data class DiscoveredPort(
    val port: Int,
    val serviceName: String,
    val url: String,
    val isPrimaryVsCode: Boolean = false
)

class PortScanner {

    fun scanPorts(
        primaryVsCodePort: Int?,
        authBridgePort: Int? = null,
        tcpProcFile: File = File("/proc/net/tcp"),
        tcp6ProcFile: File = File("/proc/net/tcp6")
    ): List<DiscoveredPort> {
        val listeningPorts = mutableSetOf<Int>()

        // 1. Always include primary VS Code server port if available
        if (primaryVsCodePort != null && primaryVsCodePort > 0) {
            listeningPorts.add(primaryVsCodePort)
        }

        // 2. Parse /proc/net/tcp & /proc/net/tcp6
        parseProcNetTcp(tcpProcFile, listeningPorts)
        parseProcNetTcp(tcp6ProcFile, listeningPorts)

        // 3. Remove internal bridge port if specified
        if (authBridgePort != null) {
            listeningPorts.remove(authBridgePort)
        }

        // 4. Map into DiscoveredPort models
        return listeningPorts.sorted().map { port ->
            val isPrimary = (port == primaryVsCodePort)
            val serviceName = when {
                isPrimary -> "VS Code Server"
                port == 3000 -> "React / Next.js Dev Server"
                port == 5173 -> "Vite Dev Server"
                port == 8000 -> "Python / HTTP Dev Server"
                port == 8080 -> "Web Dev Server"
                port == 4200 -> "Angular Dev Server"
                port == 5000 -> "Flask / Dev Server"
                port in 3001..3010 -> "Node Dev Server"
                port in 8081..8099 -> "Web Service"
                else -> "Local Service"
            }
            DiscoveredPort(
                port = port,
                serviceName = serviceName,
                url = "http://127.0.0.1:$port",
                isPrimaryVsCode = isPrimary
            )
        }
    }

    internal fun parseProcNetTcp(file: File, destination: MutableSet<Int>) {
        if (!file.exists() || !file.canRead()) {
            return
        }
        try {
            file.forEachLine { line ->
                val tokens = line.trim().split("\\s+".toRegex())
                // Header line check: sl local_address rem_address st ...
                if (tokens.size >= 4 && tokens[0] != "sl") {
                    val localAddress = tokens[1]
                    val stateHex = tokens[3]
                    // TCP_LISTEN is state 0x0A (10)
                    if (stateHex.equals("0A", ignoreCase = true)) {
                        val colonIndex = localAddress.indexOf(':')
                        if (colonIndex != -1 && colonIndex + 1 < localAddress.length) {
                            val portHex = localAddress.substring(colonIndex + 1)
                            try {
                                val port = portHex.toInt(16)
                                if (port in 1..65535) {
                                    destination.add(port)
                                }
                            } catch (ignored: NumberFormatException) {
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AvsLogger.w("PortScanner", "Failed to parse ${file.absolutePath}: ${e.message}")
        }
    }
}
