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
        tcp6ProcFile: File = File("/proc/net/tcp6"),
        guestProcessMap: Map<Int, String> = emptyMap()
    ): List<DiscoveredPort> {
        val listeningPorts = mutableSetOf<Int>()

        // 1. Always include primary VS Code server port if available
        if (primaryVsCodePort != null && primaryVsCodePort > 0) {
            listeningPorts.add(primaryVsCodePort)
        }

        // 2. Parse /proc/net/tcp & /proc/net/tcp6
        parseProcNetTcp(tcpProcFile, listeningPorts)
        parseProcNetTcp(tcp6ProcFile, listeningPorts)

        // 3. Include any ports discovered via guest process socket scan
        listeningPorts.addAll(guestProcessMap.keys)

        // 4. Remove internal bridge port if specified
        if (authBridgePort != null) {
            listeningPorts.remove(authBridgePort)
        }

        // 5. Map into DiscoveredPort models with extension server identification
        return listeningPorts.sorted().map { port ->
            val isPrimary = (port == primaryVsCodePort)
            val guestProc = guestProcessMap[port]
            val serviceName = resolveServiceName(port, isPrimary, guestProc)
            DiscoveredPort(
                port = port,
                serviceName = serviceName,
                url = "http://127.0.0.1:$port",
                isPrimaryVsCode = isPrimary
            )
        }
    }

    internal fun resolveServiceName(port: Int, isPrimary: Boolean, guestProcessName: String?): String {
        if (isPrimary) return "VS Code Server"

        val proc = guestProcessName?.lowercase()
        if (proc != null) {
            when {
                proc.contains("antigravity") -> return "Antigravity AI Agent"
                proc.contains("kilo") -> return "Kilo Server"
                proc.contains("codespace") -> return "GitHub Codespaces"
                proc.contains("jupyter") -> return "Jupyter Notebook"
                proc.contains("streamlit") -> return "Streamlit App"
                proc.contains("gradio") -> return "Gradio Web UI"
                proc.contains("vite") -> return "Vite Dev Server"
                proc.contains("next") -> return "Next.js Dev Server"
                proc.contains("live-server") -> return "Live Server"
                proc.contains("fastapi") || proc.contains("uvicorn") -> return "FastAPI Server"
                proc.contains("flask") -> return "Flask Dev Server"
                proc.contains("node") -> return "Node.js Server"
                proc.contains("python") -> return "Python Server"
            }
        }

        return when {
            port == 3000 -> "React / Next.js Dev Server"
            port == 5173 -> "Vite Dev Server"
            port == 5500 -> "Live Server"
            port == 7860 -> "Gradio Web UI"
            port == 8000 -> "Python / HTTP Dev Server"
            port == 8080 -> "Web Dev Server"
            port == 8501 -> "Streamlit App"
            port == 8888 -> "Jupyter Notebook"
            port == 4200 -> "Angular Dev Server"
            port == 5000 -> "Flask / Dev Server"
            port in 3001..3010 -> "Node Dev Server"
            port in 8081..8099 -> "Web Service"
            port in 9000..9099 -> "Dev Microservice"
            else -> "Local Service"
        }
    }

    /**
     * Parses ss -tlnp or netstat -tlnp command output to map ports to process names.
     */
    fun parseGuestSockets(output: String): Map<Int, String> {
        val result = mutableMapOf<Int, String>()
        if (output.isBlank()) return result

        val portRegex = Regex("(?::|\\.)([0-9]{2,5})\\s+")
        val procRegex = Regex("\"([^\"]+)\"")

        for (line in output.lines()) {
            if (!line.contains("LISTEN", ignoreCase = true)) continue

            val portMatch = portRegex.find(line)
            val port = portMatch?.groupValues?.get(1)?.toIntOrNull() ?: continue

            val procMatch = procRegex.find(line)
            val procName = procMatch?.groupValues?.get(1) ?: when {
                line.contains("antigravity", ignoreCase = true) -> "antigravity"
                line.contains("kilo", ignoreCase = true) -> "kilo"
                line.contains("codespace", ignoreCase = true) -> "codespaces"
                line.contains("node", ignoreCase = true) -> "node"
                line.contains("python", ignoreCase = true) -> "python"
                else -> "service"
            }

            result[port] = procName
        }
        return result
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
