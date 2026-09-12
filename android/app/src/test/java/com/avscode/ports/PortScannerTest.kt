package com.avscode.ports

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PortScannerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testParseProcNetTcp() {
        val scanner = PortScanner()
        val mockTcpFile = tempFolder.newFile("mock_tcp")

        // 0100007F:0BB8 is 127.0.0.1:3000 (0x0BB8 = 3000)
        // 00000000:1F90 is 0.0.0.0:8080 (0x1F90 = 8080)
        // 0100007F:1388 is 127.0.0.1:5000 with state 01 (ESTABLISHED, not listening)
        mockTcpFile.writeText(
            """
            sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
             0: 0100007F:0BB8 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 12345 1 0000000000000000 100 0 0 10 0
             1: 00000000:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 12346 1 0000000000000000 100 0 0 10 0
             2: 0100007F:1388 0100007F:9999 01 00000000:00000000 00:00000000 00000000  1000        0 12347 1 0000000000000000 100 0 0 10 0
            """.trimIndent()
        )

        val destination = mutableSetOf<Int>()
        scanner.parseProcNetTcp(mockTcpFile, destination)

        assertEquals(2, destination.size)
        assertTrue(destination.contains(3000))
        assertTrue(destination.contains(8080))
        assertFalse(destination.contains(5000))
    }

    @Test
    fun testScanPortsIncludesPrimaryAndExcludesBridge() {
        val scanner = PortScanner()
        val mockTcpFile = tempFolder.newFile("mock_tcp2")
        mockTcpFile.writeText(
            """
            sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
             0: 0100007F:0BB8 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 12345 1 0000000000000000 100 0 0 10 0
             1: 0100007F:8151 00000000:0000 0A 00000000:00000000 00:00000000 00000000  1000        0 12346 1 0000000000000000 100 0 0 10 0
            """.trimIndent()
        ) // 0x8151 = 33105 (internal bridge port)

        val nonExistentTcp6 = File(tempFolder.root, "non_existent")

        val ports = scanner.scanPorts(
            primaryVsCodePort = 33107,
            authBridgePort = 33105,
            tcpProcFile = mockTcpFile,
            tcp6ProcFile = nonExistentTcp6
        )

        // Should contain 3000 and 33107 (VS Code), and NOT 33105 (Bridge)
        assertEquals(2, ports.size)

        val p3000 = ports.find { it.port == 3000 }
        assertNotNull(p3000)
        assertEquals("React / Next.js Dev Server", p3000?.serviceName)
        assertEquals("http://127.0.0.1:3000", p3000?.url)
        assertFalse(p3000?.isPrimaryVsCode ?: true)

        val pVsCode = ports.find { it.port == 33107 }
        assertNotNull(pVsCode)
        assertEquals("VS Code Server", pVsCode?.serviceName)
        assertEquals("http://127.0.0.1:33107", pVsCode?.url)
        assertTrue(pVsCode?.isPrimaryVsCode ?: false)

        assertNull(ports.find { it.port == 33105 })
    }
}

