package com.avscode.runtime

import com.avscode.core.AvsLogger

/**
 * Native process spawner integrating with LinuxDroid PRoot process management.
 * Provides process group isolation (setpgid), standard I/O redirection,
 * and clean process group termination.
 */
object NativeSpawn {
    private const val TAG = "NativeSpawn"

    init {
        try {
            System.loadLibrary("avscodespawn")
            AvsLogger.d(TAG, "Loaded libavscodespawn.so successfully")
        } catch (e: UnsatisfiedLinkError) {
            try {
                System.loadLibrary("linuxdroidspawn")
                AvsLogger.d(TAG, "Loaded fallback liblinuxdroidspawn.so successfully")
            } catch (e2: UnsatisfiedLinkError) {
                AvsLogger.e(TAG, "Failed to load native spawner library", e2)
            }
        }
    }

    /**
     * Spawns a process in its own process group with redirected output.
     *
     * @param argv Command arguments array (argv[0] is executable path)
     * @param envp Environment variables in "KEY=VALUE" format
     * @param cwd Initial working directory
     * @param outputPath Path to file where stdout and stderr are redirected
     * @return IntArray of [pid, stdinPipeFd] or null on failure
     */
    external fun spawn(
        argv: Array<String>,
        envp: Array<String>,
        cwd: String,
        outputPath: String
    ): IntArray?

    /**
     * Waits for a process to change state.
     *
     * @param pid Process ID
     * @param noHang If true, returns immediately (-2) if process is still running
     * @return Exit code (0..255) on normal exit, (128+sig) on signal exit, -2 if still running, or negative error code
     */
    external fun waitFor(pid: Int, noHang: Boolean): Int

    /**
     * Sends a signal to the process group.
     *
     * @param pid Process ID (signal is sent to -pid to kill whole process group)
     * @param signal Signal number (e.g. 15 for SIGTERM, 9 for SIGKILL)
     * @return 0 on success, non-zero on error
     */
    external fun kill(pid: Int, signal: Int): Int
}

