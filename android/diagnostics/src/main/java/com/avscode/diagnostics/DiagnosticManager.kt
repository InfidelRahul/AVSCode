package com.avscode.diagnostics

import android.content.Context
import com.avscode.core.AvsLogger
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileWriter

/**
 * Diagnostic viewer and exporter for AVscode.
 * Provides UI integration and file export capabilities.
 */
class DiagnosticManager(private val context: Context) {
    
    private val TAG = "AVscode.DiagnosticMgr"
    
    /**
     * Collect and export diagnostics to a file.
     * Returns the file path or null if export failed.
     */
    suspend fun exportDiagnosticsToFile(): String? {
        return try {
            val report = RuntimeDiagnostics.collectFullDiagnostics()
            val textReport = RuntimeDiagnostics.exportToText(report)
            
            val diagnosticsDir = File(context.filesDir, "diagnostics")
            if (!diagnosticsDir.exists()) {
                diagnosticsDir.mkdirs()
            }
            
            val timestamp = System.currentTimeMillis()
            val file = File(diagnosticsDir, "diagnostics_${timestamp}.txt")
            
            FileWriter(file).use { writer ->
                writer.write(textReport)
            }
            
            AvsLogger.i(TAG, "Diagnostics exported to: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            AvsLogger.e(TAG, "Failed to export diagnostics", e)
            null
        }
    }
    
    /**
     * Get current diagnostics as text without saving to file.
     */
    suspend fun getDiagnosticsAsText(): String {
        val report = RuntimeDiagnostics.collectFullDiagnostics()
        return RuntimeDiagnostics.exportToText(report)
    }
    
    /**
     * Check overall system health.
     * Returns a summary of critical issues.
     */
    suspend fun getHealthSummary(): HealthSummary {
        val report = RuntimeDiagnostics.collectFullDiagnostics()
        
        val issues = mutableListOf<String>()
        var criticalIssues = 0
        var warnings = 0
        
        // Check storage
        if (report.storageInfo.externalStorageFree < 2L * 1024 * 1024 * 1024) {
            issues.add("CRITICAL: Less than 2GB free storage")
            criticalIssues++
        } else if (report.storageInfo.externalStorageFree < 5L * 1024 * 1024 * 1024) {
            issues.add("WARNING: Less than 5GB free storage")
            warnings++
        }
        
        // Check rootfs
        if (!report.rootfsInfo.installed) {
            issues.add("INFO: Rootfs not installed (first launch expected)")
        } else {
            if (!report.rootfsInfo.hasBinBash) {
                issues.add("CRITICAL: /bin/bash missing in rootfs")
                criticalIssues++
            }
            if (!report.rootfsInfo.hasHomeDir) {
                issues.add("WARNING: /home/user directory missing")
                warnings++
            }
        }
        
        // Check code-server
        if (report.vscodeInfo.installed) {
            if (!report.vscodeInfo.binaryCanExecute) {
                issues.add("CRITICAL: code-server binary not executable")
                criticalIssues++
            }
        }
        
        // Check PRoot
        if (!report.pruntimeInfo.nativeLibraryLoaded) {
            issues.add("CRITICAL: PRoot native library not loaded")
            criticalIssues++
        }
        
        // Check recent errors in logs
        val recentErrors = report.logEntries.filter { 
            it.level == AvsLogger.LogEntry.Level.ERROR && 
            (System.currentTimeMillis() - it.timestamp) < 300000 // Last 5 minutes
        }
        
        if (recentErrors.isNotEmpty()) {
            issues.add("WARNING: ${recentErrors.size} errors in last 5 minutes")
            warnings++
        }
        
        return HealthSummary(
            isHealthy = criticalIssues == 0,
            criticalIssues = criticalIssues,
            warnings = warnings,
            issues = issues,
            canStart = report.rootfsInfo.installed && 
                      report.pruntimeInfo.nativeLibraryLoaded &&
                      report.storageInfo.externalStorageFree > 2L * 1024 * 1024 * 1024
        )
    }
    
    /**
     * Clear old diagnostic files.
     */
    fun clearOldDiagnostics(maxAgeHours: Long = 24) {
        try {
            val diagnosticsDir = File(context.filesDir, "diagnostics")
            if (!diagnosticsDir.exists()) return
            
            val cutoffTime = System.currentTimeMillis() - (maxAgeHours * 60 * 60 * 1000)
            
            diagnosticsDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoffTime) {
                    file.delete()
                    AvsLogger.d(TAG, "Deleted old diagnostic file: ${file.name}")
                }
            }
        } catch (e: Exception) {
            AvsLogger.e(TAG, "Failed to clear old diagnostics", e)
        }
    }
}

/**
 * Summary of system health check.
 */
data class HealthSummary(
    val isHealthy: Boolean,
    val criticalIssues: Int,
    val warnings: Int,
    val issues: List<String>,
    val canStart: Boolean
)
