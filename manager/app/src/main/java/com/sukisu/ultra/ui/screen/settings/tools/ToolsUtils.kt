package com.sukisu.ultra.ui.screen.settings.tools

import android.content.Context
import android.net.Uri
import com.topjohnwu.superuser.Shell
import com.sukisu.ultra.ui.util.ksudExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

fun isSelinuxPermissive(): Boolean {
    val (exitCode, output) = ksudExec("shell -c getenforce", captureOutput = true)
    return when {
        exitCode == 0 && output.lowercase() == "permissive" -> true
        else -> false
    }
}

fun setSelinuxPermissive(permissive: Boolean): Boolean {
    val target = if (permissive) "0" else "1"
    val (exitCode, _) = ksudExec("shell -c 'setenforce $target'")
    return exitCode == 0
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