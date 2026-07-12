package com.sukisu.ultra.ui.util

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Returns the raw SELinux status string ("Enforcing", "Permissive", "Disabled", or "Unknown").
 * Safe to call from any thread (IO recommended).
 */
fun getSELinuxStatusRaw(): String {
    return try {
        val proc = Runtime.getRuntime().exec("getenforce")
        val stdout = BufferedReader(InputStreamReader(proc.inputStream)).readText().trim()
        proc.waitFor()
        when (stdout) {
            "Enforcing", "Permissive", "Disabled" -> stdout
            else -> "Unknown"
        }
    } catch (e: Exception) {
        "Unknown"
    }
}
