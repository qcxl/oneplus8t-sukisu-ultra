package com.sukisu.ultra.ui.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.os.Parcelable
import android.os.SystemClock
import android.provider.OpenableColumns
import android.system.Os
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.parcelize.Parcelize
import com.sukisu.ultra.BuildConfig
import com.sukisu.ultra.Natives
import com.sukisu.ultra.ksuApp
import org.json.JSONArray
import java.io.File

/**
 * @author weishu
 * @date 2023/1/1.
 */
private const val TAG = "KsuCli"

fun getKsuDaemonPath(): String {
    // Prefer bundled libksud.so (in app's native lib dir — always accessible,
    // no /data/adb/ traversal needed). Avoids detection via /data/adb/ scan.
    // Note: /data/adb/ permission is 700 (root only) for anti-detection.
    val bundled = "${ksuApp.applicationInfo.nativeLibraryDir}/libksud.so"
    // Skip canExecute() — it lies on some ROMs. Just try bundled first.
    return bundled
}

data class FlashResult(val code: Int, val err: String, val showReboot: Boolean) {
    constructor(result: Shell.Result, showReboot: Boolean) : this(result.code, result.err.joinToString("\n"), showReboot)
    constructor(result: Shell.Result) : this(result, result.isSuccess)
}

object KsuCli {
    val SHELL: Shell by lazy { createRootShell() }
    val GLOBAL_MNT_SHELL: Shell by lazy { createRootShell(true) }
}

fun getRootShell(globalMnt: Boolean = false): Shell {
    return if (globalMnt) KsuCli.GLOBAL_MNT_SHELL else {
        KsuCli.SHELL
    }
}

inline fun <T> withNewRootShell(
    globalMnt: Boolean = false,
    block: Shell.() -> T
): T {
    return createRootShell(globalMnt).use(block)
}

fun Uri.getFileName(context: Context): String? {
    var fileName: String? = null
    val contentResolver: ContentResolver = context.contentResolver
    val cursor: Cursor? = contentResolver.query(this, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            fileName = it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
    }
    return fileName
}

fun createRootShellBuilder(globalMnt: Boolean = false): Shell.Builder {
    return Shell.Builder.create().run {
        val cmd = buildString {
            append(getKsuDaemonPath())
            append(" debug su")
            if (globalMnt) append(" -g")
            append(" || ")
            append("su")
            if (globalMnt) append(" --mount-master")
            append(" || ")
            append("sh")
        }
        setCommands("sh", "-c", cmd)
    }
}

fun createRootShell(globalMnt: Boolean = false): Shell {
    Shell.enableVerboseLogging = BuildConfig.DEBUG
    return runCatching {
        createRootShellBuilder(globalMnt).build()
    }.getOrElse { e ->
        Log.w(TAG, "su failed: ", e)
        Shell.Builder.create().apply {
            if (globalMnt) setFlags(Shell.FLAG_MOUNT_MASTER)
        }.build()
    }
}

fun ksudExec(args: String, captureOutput: Boolean = false): Pair<Int, String> {
    return try {
        val cmd = "${getKsuDaemonPath()} $args"
        val proc = Runtime.getRuntime().exec(cmd)
        val output = if (captureOutput) {
            BufferedReader(InputStreamReader(proc.inputStream)).readText().trim()
        } else ""
        val exitCode = proc.waitFor()
        exitCode to output
    } catch (e: Exception) {
        Log.e(TAG, "ksudExec failed: $args", e)
        -1 to ""
    }
}

/**
 * Execute a shell command via ksud's interactive root shell (stdin pipe).
 * Unlike ksudExec(), this works for arbitrary shell commands (setenforce, ls, etc.)
 * since it pipes the command to `ksud debug su` instead of expecting a ksud subcommand.
 */
fun ksudExecShell(command: String): Pair<Int, String> {
    return try {
        val proc = Runtime.getRuntime().exec("${getKsuDaemonPath()} debug su")
        proc.outputStream.write("$command\nexit\n".toByteArray())
        proc.outputStream.flush()
        proc.outputStream.close()
        val output = BufferedReader(InputStreamReader(proc.inputStream)).readText().trim()
        val exitCode = proc.waitFor()
        exitCode to output
    } catch (e: Exception) {
        Log.e(TAG, "ksudExecShell failed: $command", e)
        -1 to ""
    }
}

fun execKsud(args: String, newShell: Boolean = false): Boolean {
    val (exitCode, _) = ksudExec(args)
    return exitCode == 0
}

suspend fun getFeatureStatus(feature: String): String = withContext(Dispatchers.IO) {
    val (exitCode, output) = ksudExec("feature check $feature", captureOutput = true)
    val status = output.lines().firstOrNull()?.trim().orEmpty()
    // When SELinux Enforcing, Runtime.exec() to /data/adb/ksu/ksud is blocked,
    // so ksudExec returns -1/"". The kernel has all features compiled in, so
    // assume "supported" on exec failure rather than showing all switches disabled.
    if (status.isEmpty() && exitCode != 0) "supported" else status
}

suspend fun getFeaturePersistValue(feature: String): Long? = withContext(Dispatchers.IO) {
    val (_, output) = ksudExec("feature get --config $feature", captureOutput = true)
    val valueLine = output.lines().firstOrNull { it.trim().startsWith("Value:") } ?: return@withContext null
    valueLine.substringAfter("Value:").trim().toLongOrNull()
}

fun install() {
    val start = SystemClock.elapsedRealtime()
    val libadbroot = File(ksuApp.applicationInfo.nativeLibraryDir, "libadbroot.so").absolutePath
    val shell = getRootShell()
    val result = ShellUtils.fastCmdResult(shell, "${getKsuDaemonPath()} install --libadbroot $libadbroot --data-path ${ksuApp.applicationInfo.deviceProtectedDataDir}")
    Log.w(TAG, "install result: $result, cost: ${SystemClock.elapsedRealtime() - start}ms")
}

fun listModules(): String {
    val shell = getRootShell()
    val out = shell.newJob().add("${getKsuDaemonPath()} module list")
        .to(ArrayList<String>(), null).exec().out
    return out.joinToString("\n").trim().ifBlank { "[]" }
}

fun getModuleCount(): Int {
    val result = listModules()
    runCatching {
        val array = JSONArray(result)
        return array.length()
    }.getOrElse { return 0 }
}

fun getSuperuserCount(): Int {
    return Natives.getSuperuserCount()
}

fun toggleModule(id: String, enable: Boolean): Boolean {
    val cmd = if (enable) {
        "module enable $id"
    } else {
        "module disable $id"
    }
    val shell = getRootShell()
    val result = ShellUtils.fastCmdResult(shell, "${getKsuDaemonPath()} $cmd")
    Log.i(TAG, "$cmd result: $result")
    return result
}

fun undoUninstallModule(id: String): Boolean {
    val cmd = "module undo-uninstall $id"
    val shell = getRootShell()
    val result = ShellUtils.fastCmdResult(shell, "${getKsuDaemonPath()} $cmd")
    Log.i(TAG, "undo uninstall module $id result: $result")
    return result
}

fun uninstallModule(id: String): Boolean {
    val cmd = "module uninstall $id"
    val shell = getRootShell()
    val result = ShellUtils.fastCmdResult(shell, "${getKsuDaemonPath()} $cmd")
    Log.i(TAG, "uninstall module $id result: $result")
    return result
}

private fun flashWithIO(
    cmd: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): Shell.Result {

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = withNewRootShell {
        newJob().add(cmd).to(stdoutCallback, stderrCallback).exec()
    }
    Log.i(TAG, "flashWithIO cmd=$cmd code=${result.code} out=${result.out} err=${result.err}")
    return result
}

fun flashModule(
    uri: Uri,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit
): FlashResult {
    val resolver = ksuApp.contentResolver
    val inputStream = resolver.openInputStream(uri)
        ?: throw IllegalArgumentException("Cannot open input stream for $uri")
    inputStream.use { stream ->
        val file = File(ksuApp.cacheDir, "module.zip")
        file.outputStream().use { output ->
            stream.copyTo(output)
        }
        val cmd = "module install ${file.absolutePath}"
        val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
        Log.i("KernelSU", "install module $uri result: $result")

        file.delete()

        return FlashResult(result)
    }
}

fun runModuleAction(
    moduleId: String, onStdout: (String) -> Unit, onStderr: (String) -> Unit
): Boolean {
    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStdout(s ?: "")
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            onStderr(s ?: "")
        }
    }

    val result = Shell.cmd("${getKsuDaemonPath()} module action $moduleId")
        .to(stdoutCallback, stderrCallback).exec()

    Log.i("KernelSU", "Module runAction result: $result")

    return result.isSuccess
}

fun restoreBoot(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO("${getKsuDaemonPath()} boot-restore -f", onStdout, onStderr)
    return FlashResult(result)
}

fun uninstallPermanently(
    onStdout: (String) -> Unit, onStderr: (String) -> Unit
): FlashResult {
    val result = flashWithIO("${getKsuDaemonPath()} uninstall --package-name ${BuildConfig.APPLICATION_ID}", onStdout, onStderr)
    return FlashResult(result)
}

@Parcelize
sealed class LkmSelection : Parcelable {
    @Parcelize
    data class LkmUri(val uri: Uri) : LkmSelection()

    @Parcelize
    data class KmiString(val value: String) : LkmSelection()

    @Parcelize
    data object KmiNone : LkmSelection()
}

fun installBoot(
    bootUri: Uri?,
    lkm: LkmSelection,
    ota: Boolean,
    partition: String?,
    allowShell: Boolean,
    enableAdb: Boolean,
    forceBackup: Boolean,
    spoofRelease: String,
    spoofVersion: String,
    onStdout: (String) -> Unit,
    onStderr: (String) -> Unit,
): FlashResult {
    val resolver = ksuApp.contentResolver

    val bootFile = bootUri?.let { uri ->
        val inputStream = resolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open input stream for $uri")
        inputStream.use { stream ->
            val bootFile = File(ksuApp.cacheDir, "boot.img")
            bootFile.outputStream().use { output ->
                stream.copyTo(output)
            }

            bootFile
        }
    }

    var cmd = "boot-patch"

    cmd += if (bootFile == null) {
        // no boot.img, use -f to flash
        " -f"
    } else {
        " -b ${bootFile.absolutePath}"
    }

    if (allowShell) {
        cmd += " --allow-shell"
    }

    if (enableAdb) {
        cmd += " --enable-adbd"
    }

    if (spoofRelease.isNotBlank()) {
        cmd += " --spoof-release ${spoofRelease.shellArg()}"
    }

    if (spoofVersion.isNotBlank()) {
        cmd += " --spoof-version ${spoofVersion.shellArg()}"
    }

    if (ota) {
        cmd += " -u"
    }

    if (forceBackup) {
        cmd += " --backup"
    }

    var lkmFile: File? = null
    when (lkm) {
        is LkmSelection.LkmUri -> {
            lkmFile = resolver.openInputStream(lkm.uri)?.let { lkmStream ->
                lkmStream.use { stream ->
                    val file = File(ksuApp.cacheDir, "kernelsu-tmp-lkm.ko")
                    file.outputStream().use { output ->
                        stream.copyTo(output)
                    }
                    file
                }
            } ?: throw IllegalArgumentException("Cannot open input stream for ${lkm.uri}")
            cmd += " -m ${lkmFile.absolutePath}"
        }

        is LkmSelection.KmiString -> {
            cmd += " --kmi ${lkm.value}"
        }

        LkmSelection.KmiNone -> {
            // do nothing
        }
    }

    // output dir
    if (bootFile != null) {
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        cmd += " -o $downloadsDir"
    }

    partition?.let { part ->
        cmd += " --partition $part"
    }

    val result = flashWithIO("${getKsuDaemonPath()} $cmd", onStdout, onStderr)
    Log.i("KernelSU", "install boot result: ${result.isSuccess}")

    bootFile?.delete()
    lkmFile?.delete()

    // if boot uri is empty, it is direct install, when success, we should show reboot button
    val showReboot = bootUri == null && result.isSuccess // we create a temporary val here, to avoid calc showReboot double
    if (showReboot) { // because we decide do not update ksud when startActivity
        install() // install ksud here
    }
    return FlashResult(result, showReboot)
}

private fun String.shellArg(): String = "'${replace("'", "'\\''")}'"

fun reboot(reason: String = "") {
    if (reason == "soft_reboot") {
        execKsud("soft-reboot", true)
        return
    }
    val shell = getRootShell()
    if (reason == "recovery") {
        // KEYCODE_POWER = 26, hide incorrect "Factory data reset" message
        ShellUtils.fastCmd(shell, "/system/bin/input keyevent 26")
    }
    ShellUtils.fastCmd(shell, "/system/bin/svc power reboot $reason || /system/bin/reboot $reason")
}

fun rootAvailable(): Boolean {
    // If KSU native communication works, root is definitely available
    if (Natives.version > 0 || Natives.kernelUAPIVersion > 0) return true
    val shell = getRootShell()
    return shell.isRoot
}

suspend fun getCurrentKmi(): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info current-kmi"
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd")
}

suspend fun getSupportedKmis(): List<String> = withContext(Dispatchers.IO) {
    val (_, output) = ksudExec("boot-info supported-kmis", captureOutput = true)
    output.lines().filter { it.isNotBlank() }.map { it.trim() }
}

suspend fun isAbDevice(): Boolean = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = "boot-info is-ab-device"
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim().toBoolean()
}

suspend fun getDefaultPartition(): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    if (shell.isRoot) {
        val cmd = "boot-info default-partition"
        ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
    } else {
        if (!Os.uname().release.contains("android12-")) "init_boot" else "boot"
    }
}

suspend fun getSlotSuffix(ota: Boolean): String = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val cmd = if (ota) {
        "boot-info slot-suffix --ota"
    } else {
        "boot-info slot-suffix"
    }
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} $cmd").trim()
}

suspend fun getAvailablePartitions(): List<String> = withContext(Dispatchers.IO) {
    val (_, output) = ksudExec("boot-info available-partitions", captureOutput = true)
    output.lines().filter { it.isNotBlank() }.map { it.trim() }
}

fun hasMagisk(): Boolean {
    val shell = getRootShell()
    val result = ShellUtils.fastCmdResult(shell, "${getKsuDaemonPath()} debug exec which magisk")
    Log.i(TAG, "has magisk: $result")
    return result
}

fun isSepolicyValid(rules: String?): Boolean {
    if (rules == null) return true
    val shell = getRootShell()
    return ShellUtils.fastCmdResult(shell, "${getKsuDaemonPath()} sepolicy check '$rules'")
}

fun getSepolicy(pkg: String): String {
    val (exitCode, output) = ksudExec("profile get-sepolicy $pkg", captureOutput = true)
    Log.i(TAG, "code: $exitCode, out: $output")
    return output
}

fun setSepolicy(pkg: String, rules: String): Boolean {
    val shell = getRootShell()
    val result = ShellUtils.fastCmdResult(shell, "${getKsuDaemonPath()} profile set-sepolicy $pkg '$rules'")
    Log.i(TAG, "set sepolicy result: $result")
    return result
}

fun listAppProfileTemplates(): List<String> {
    val shell = getRootShell()
    val out = shell.newJob().add("${getKsuDaemonPath()} profile list-templates")
        .to(ArrayList<String>(), null).exec().out
    return out.filter { it.isNotBlank() }
}

fun getAppProfileTemplate(id: String): String {
    val shell = getRootShell()
    val out = shell.newJob().add("${getKsuDaemonPath()} profile get-template '$id'")
        .to(ArrayList<String>(), null).exec().out
    return out.joinToString("\n").trim()
}

fun setAppProfileTemplate(id: String, template: String): Boolean {
    val escaped = template.replace("'", "'\\''")
    val shell = getRootShell()
    return ShellUtils.fastCmdResult(shell, "${getKsuDaemonPath()} profile set-template '$id' '$escaped'")
}

fun deleteAppProfileTemplate(id: String): Boolean {
    val shell = getRootShell()
    return ShellUtils.fastCmdResult(shell, "${getKsuDaemonPath()} profile delete-template '$id'")
}

fun forceStopApp(packageName: String, userId: Int? = null) {
    val userArg = userId?.let { " --user $it" } ?: ""
    val shell = getRootShell()
    ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} debug exec am force-stop$userArg $packageName")
}

fun launchApp(packageName: String, userId: Int? = null) {
    val shell = getRootShell()
    val userArg = userId?.let { " --user $it" } ?: ""
    val result =
        shell.newJob()
            .add("cmd package resolve-activity --brief$userArg $packageName | tail -n 1 | xargs cmd activity start-activity$userArg -n")
            .exec()
    Log.i(TAG, "launch $packageName result: $result")
}

fun restartApp(packageName: String, userId: Int? = null) {
    forceStopApp(packageName, userId)
    launchApp(packageName, userId)
}

// KPM控制
fun loadKpmModule(path: String, args: String? = null): Boolean {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} kpm load $path ${args ?: ""}"
    return ShellUtils.fastCmdResult(shell, cmd)
}

fun unloadKpmModule(name: String): Boolean {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} kpm unload $name"
    return ShellUtils.fastCmdResult(shell, cmd)
}

fun getKpmModuleCount(): Int {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} kpm num"
    val result = ShellUtils.fastCmd(shell, cmd)
    return result.trim().toIntOrNull() ?: 0
}

fun runCmd(shell: Shell, cmd: String): String {
    val ksudArgs = cmd.removePrefix("${getKsuDaemonPath()} ")
    val (_, output) = ksudExec(ksudArgs, captureOutput = true)
    return output
}

suspend fun streamFile(path: String): List<String> = withContext(Dispatchers.IO) {
    val shell = getRootShell()
    val outLines = mutableListOf<String>()

    val stdoutCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            if (s != null) outLines.add(s)
        }
    }

    val stderrCallback: CallbackList<String?> = object : CallbackList<String?>() {
        override fun onAddElement(s: String?) {
            // ignore stderr for now
        }
    }

    shell.newJob().add("cat $path || true").to(stdoutCallback, stderrCallback).exec()
    outLines
}

fun listKpmModules(): String {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} kpm list"
    return try {
        runCmd(shell, cmd).trim()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to list KPM modules", e)
        ""
    }
}

fun getKpmModuleInfo(name: String): String {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} kpm info $name"
    return try {
        runCmd(shell, cmd).trim()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get KPM module info: $name", e)
        ""
    }
}

fun controlKpmModule(name: String, args: String? = null): Int {
    val shell = getRootShell()
    val cmd = """${getKsuDaemonPath()} kpm control $name "${args ?: ""}""""
    val result = runCmd(shell, cmd)
    return result.trim().toIntOrNull() ?: -1
}

fun getKpmVersion(): String {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} kpm version"
    val result = ShellUtils.fastCmd(shell, cmd)
    return result.trim()
}

fun getSuSFSStatus(): String {
    val shell = getRootShell()
    return ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} susfs status").trim()
}

fun getSuSFSVersion(): String {
    val shell = getRootShell()
    val result = ShellUtils.fastCmd(shell, "${getKsuDaemonPath()} susfs version")
    return result
}

fun getSuSFSFeatures(): String {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} susfs features"
    return runCmd(shell, cmd)
}

fun spoofKernelUname(release: String, version: String): Boolean {
    fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} kernel spoof-uname --release ${shellQuote(release)} --version ${shellQuote(version)}"
    val result = ShellUtils.fastCmdResult(shell, cmd)
    Log.i(TAG, "kernel spoof-uname result: $result")
    return result
}

fun addUmountPath(path: String, flags: Int): Boolean {
    val shell = getRootShell()
    val flagsArg = if (flags >= 0) "--flags $flags" else ""
    val cmd = "${getKsuDaemonPath()} umount add $path $flagsArg"
    val result = ShellUtils.fastCmdResult(shell, cmd)
    Log.i(TAG, "add umount path $path result: $result")
    return result
}

fun removeUmountPath(path: String): Boolean {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} umount remove $path"
    val result = ShellUtils.fastCmdResult(shell, cmd)
    Log.i(TAG, "remove umount path $path result: $result")
    return result
}

fun listUmountPaths(): String {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} umount list"
    return try {
        runCmd(shell, cmd).trim()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to list umount paths", e)
        ""
    }
}

fun clearCustomUmountPaths(): Boolean {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} umount clear-custom"
    val result = ShellUtils.fastCmdResult(shell, cmd)
    Log.i(TAG, "clear custom umount paths result: $result")
    return result
}

fun saveUmountConfig(): Boolean {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} umount save"
    val result = ShellUtils.fastCmdResult(shell, cmd)
    Log.i(TAG, "save umount config result: $result")
    return result
}

fun applyUmountConfigToKernel(): Boolean {
    val shell = getRootShell()
    val cmd = "${getKsuDaemonPath()} umount apply"
    val result = ShellUtils.fastCmdResult(shell, cmd)
    Log.i(TAG, "apply umount config to kernel result: $result")
    return result
}

// 检查 KPM 版本是否可用
@Composable
fun rememberKpmAvailable(): Boolean {
    var cachedVersion by rememberSaveable { mutableStateOf("") }
    val kpmVersion by produceState(initialValue = cachedVersion) {
        val result = withContext(Dispatchers.IO) {
            runCatching { getKpmVersion() }.getOrElse { "" }
        }
        cachedVersion = result
        value = result
    }
    return kpmVersion.isNotEmpty() && !kpmVersion.contains("Error", ignoreCase = true)
}

data class BootConfig(
    val allowShell: Boolean = false,
    val spoofRelease: String = "",
    val spoofVersion: String = "",
)

// 读取镜像中的 ksu_config 参数
suspend fun getBootConfig(): BootConfig = withContext(Dispatchers.IO) {
    val (_, output) = ksudExec("boot-info read-config", captureOutput = true)
    val out = output.lines()

    var allowShell = false
    var spoofRelease = ""
    var spoofVersion = ""

    for (line in out) {
        when {
            line.startsWith("allow_shell=") -> allowShell = line.substringAfter("=").trim() == "1"
            line.startsWith("spoof_release=") -> {
                spoofRelease = parseQuotedValue(line.substringAfter("spoof_release="))
            }
            line.startsWith("spoof_version=") -> {
                spoofVersion = parseQuotedValue(line.substringAfter("spoof_version="))
            }
        }
    }

    BootConfig(allowShell, spoofRelease, spoofVersion)
}

private fun parseQuotedValue(value: String): String {
    val trimmed = value.trim()
    return if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
        trimmed.substring(1, trimmed.length - 1)
    } else if (trimmed.startsWith("'") && trimmed.endsWith("'")) {
        trimmed.substring(1, trimmed.length - 1)
    } else {
        trimmed
    }
}
