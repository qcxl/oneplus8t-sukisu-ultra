package com.sukisu.ultra.ui.util

import java.io.File

fun getSELinuxStatusRaw(): String {
    if (!File("/sys/fs/selinux").exists()) return "Disabled"
    try {
        val value = File("/sys/fs/selinux/enforce").readText().trim()
        if (value == "0") return "Permissive"
        if (value == "1") return "Enforcing"
    } catch (_: Exception) {}
    try {
        val proc = Runtime.getRuntime().exec("getenforce")
        val stdout = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        if (stdout in listOf("Enforcing", "Permissive", "Disabled")) return stdout
        if (stdout.isEmpty()) return "Enforcing"
    } catch (_: Exception) {}
    val (_, out) = ksudExecShell("cat /sys/fs/selinux/enforce")
    return when (out.trim()) {
        "0" -> "Permissive"
        "1" -> "Enforcing"
        else -> "Enforcing"
    }
}
