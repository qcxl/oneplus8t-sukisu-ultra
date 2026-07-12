package com.sukisu.ultra.ui.util

import java.io.File

/**
 * Returns the raw SELinux status string ("Enforcing", "Permissive", "Disabled", or "Unknown").
 * Tries multiple approaches in order of reliability.
 */
fun getSELinuxStatusRaw(): String {
    // Method 1: read /sys/fs/selinux/enforce directly (works when SELinux app policy allows)
    try {
        // SELinux sysfs node is world-readable on most kernels
        val value = File("/sys/fs/selinux/enforce").useLines { lines ->
            lines.firstOrNull()?.trim()
        }
        if (value == "0") return "Permissive"
        if (value == "1") return "Enforcing"
    } catch (_: Exception) {}
    // Method 2: direct getenforce (works when Permissive or when policy allows exec)
    try {
        val proc = Runtime.getRuntime().exec("getenforce")
        val stdout = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        if (stdout in listOf("Enforcing", "Permissive", "Disabled")) return stdout
    } catch (_: Exception) {}
    // Method 3: read enforce file via ksud root shell
    val (_, out) = ksudExecShell("cat /sys/fs/selinux/enforce")
    return when (out.trim()) {
        "0" -> "Permissive"
        "1" -> "Enforcing"
        else -> "Unknown"
    }
}
