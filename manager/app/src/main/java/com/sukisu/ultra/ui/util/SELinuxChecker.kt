package com.sukisu.ultra.ui.util

/**
 * Returns the raw SELinux status string ("Enforcing", "Permissive", "Disabled", or "Unknown").
 * Falls back to reading /sys/fs/selinux/enforce via ksud root shell if direct getenforce fails.
 */
fun getSELinuxStatusRaw(): String {
    // Method 1: direct getenforce
    try {
        val proc = Runtime.getRuntime().exec("getenforce")
        val stdout = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        if (stdout in listOf("Enforcing", "Permissive", "Disabled")) return stdout
    } catch (_: Exception) {}
    // Method 2: read enforce file via ksud root shell (works even when enforcing blocks exec)
    val (_, out) = ksudExecShell("cat /sys/fs/selinux/enforce")
    return when (out.trim()) {
        "0" -> "Permissive"
        "1" -> "Enforcing"
        else -> "Unknown"
    }
}
