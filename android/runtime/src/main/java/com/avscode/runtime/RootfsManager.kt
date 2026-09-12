package com.avscode.runtime

/**
 * Common abstraction for rootfs inspection.
 */
interface RootfsManager {
    fun isInstalled(): Boolean
    fun getRootfsPath(): String
}
