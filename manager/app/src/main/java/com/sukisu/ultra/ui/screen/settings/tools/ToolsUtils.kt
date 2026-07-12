package com.sukisu.ultra.ui.screen.settings.tools

import android.content.Context
import android.net.Uri
import com.topjohnwu.superuser.Shell
import com.sukisu.ultra.Natives
import com.sukisu.ultra.ui.util.ksudExecShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader

fun isSelinuxPermissive(): Boolean {
    // Method 1: kernel IOCTL (KSU_FEATURE_SET_SELINUX_ENFORCE = 5) — bypasses root shell
    try {
        return !Natives.isSelinuxEnforce()
    } catch (_: Exception) {}
    // Method 2: direct sysfs read (world-readable)
    try {
        val value = java.io.File("/sys/fs/selinux/enforce").useLines { lines ->
            lines.firstOrNull()?.trim()
        }
        if (value == "0") return true
        if (value == "1") return false
    } catch (_: Exception) {}
    // Method 3: getenforce via Runtime.exec
    try {
        val proc = Runtime.getRuntime().exec("getenforce")
        val output = proc.inputStream.bufferedReader().readText().trim().lowercase()
        proc.waitFor()
        if (output == "permissive") return true
    } catch (_: Exception) {}
    // Method 4: ksud root shell
    val (_, out) = ksudExecShell("cat /sys/fs/selinux/enforce")
    return out.trim() == "0"
}

fun setSelinuxPermissive(permissive: Boolean): Boolean {
    val target = if (permissive) "0" else "1"
    // Method 1: kernel IOCTL (KSU_FEATURE_SET_SELINUX_ENFORCE = 5) — bypasses root shell entirely
    try {
        if (Natives.setSelinuxEnforce(!permissive)) return true
    } catch (_: Exception) {}
    // Method 2: write /sys/fs/selinux/enforce directly (world-writable with KSU policy)
    try {
        java.io.File("/sys/fs/selinux/enforce").writeText(target)
        return true
    } catch (_: Exception) {}
    // Method 3: ksud root shell pipe
    val (ec1, _) = ksudExecShell("setenforce $target")
    if (ec1 == 0) return true
    // Method 4: try su -c directly
    try {
        val proc = Runtime.getRuntime().exec("su -c setenforce $target")
        if (proc.waitFor() == 0) return true
    } catch (_: Exception) {}
    return false
}

suspend fun backupAllowlistToUri(context: Context, targetUri: Uri): Boolean = withContext(Dispatchers.IO) {
    val tempFile = File(context.cacheDir, "allowlist_backup_tmp.bin")
    try {
        if (!copyAllowlistToFile(tempFile)) return@withContext false
        return@withContext runCatching {
            context.contentResolver.openOutputStream(targetUri, "w")?.use { output ->
                tempFile.inputStream().use { input ->
                    input.copyTo(output)
                }
                true
            } ?: false
        }.getOrElse { false }
    } finally {
        tempFile.delete()
    }
}

suspend fun restoreAllowlistFromUri(context: Context, sourceUri: Uri): Boolean = withContext(Dispatchers.IO) {
    val tempFile = File(context.cacheDir, "allowlist_restore_tmp.bin")
    try {
        val downloaded = runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
                true
            } ?: false
        }.getOrElse { false }
        if (!downloaded) return@withContext false
        return@withContext copyFileToAllowlist(tempFile)
    } finally {
        tempFile.delete()
    }
}

private suspend fun copyAllowlistToFile(targetFile: File): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        targetFile.parentFile?.mkdirs()
        val result = Shell.cmd(
            "cp /data/adb/ksu/.allowlist \"${targetFile.absolutePath}\"",
            "chmod 0644 \"${targetFile.absolutePath}\""
        ).exec()
        result.isSuccess
    }.getOrDefault(false)
}

private suspend fun copyFileToAllowlist(sourceFile: File): Boolean = withContext(Dispatchers.IO) {
    if (!sourceFile.exists()) return@withContext false
    runCatching {
        val result = Shell.cmd(
            "cp \"${sourceFile.absolutePath}\" /data/adb/ksu/.allowlist",
            "chmod 0644 /data/adb/ksu/.allowlist"
        ).exec()
        result.isSuccess
    }.getOrDefault(false)
}