package com.sukisu.ultra.ui.screen.home

import android.content.Context
import android.os.Build
import android.system.Os
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sukisu.ultra.Natives
import com.sukisu.ultra.Natives.isManager
import com.sukisu.ultra.ui.util.getSuSFSStatus
import com.sukisu.ultra.ui.util.getSuSFSVersion

data class ManagerVersion(
    val versionName: String,
    val versionCode: Long
)

data class SystemInfo(
    val kernelVersion: String = "",
    val managerVersion: String = "",
    val deviceModel: String = "",
    val kernelFullVersion: String? = null,
    val fingerprint: String = "",
    val selinuxStatus: String = "",
    val seccompStatus: Int = 0
)


fun getManagerVersion(context: Context): ManagerVersion {
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)!!
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
    return ManagerVersion(
        versionName = packageInfo.versionName!!,
        versionCode = versionCode
    )
}

enum class SusfsStatus {
    Idle, Supported, Unsupported, Error
}

data class SusfsInfoState(
    val status: SusfsStatus = SusfsStatus.Idle,
    val detail: String = "",
)

@Composable
fun rememberSusfsInfo(
    manualHookLabel: String,
    inlineHookLabel: String,
): SusfsInfoState {
    return produceState(initialValue = SusfsInfoState(), manualHookLabel, inlineHookLabel) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val supported = getSuSFSStatus().equals("true", ignoreCase = true)
                if (supported) {
                    val version = getSuSFSVersion().trim()
                    val hookLabel = when (val type = Natives.getHookType()) {
                        "Manual" -> manualHookLabel
                        "Inline" -> inlineHookLabel
                        else -> type
                    }.takeIf { it.isNotBlank() }?.let { "($it)" }.orEmpty()
                    SusfsInfoState(
                        status = SusfsStatus.Supported,
                        detail = listOf(version, hookLabel)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                    )
                } else {
                    SusfsInfoState(
                        status = SusfsStatus.Unsupported,
                        detail = ""
                    )
                }
            }.getOrElse {
                SusfsInfoState(status = SusfsStatus.Error)
            }
        }
    }.value
}

@Composable
fun rememberHookTypeLabel(
    manualHookText: String,
    inlineHookText: String,
    tracepointHookText: String,
    unknownHookText: String,
): String? {
    return produceState<String?>(initialValue = null, manualHookText, inlineHookText, tracepointHookText, unknownHookText) {
        if (!isManager) {
            value = null
            return@produceState
        }
        value = withContext(Dispatchers.IO) {
            val rawType = runCatching { Natives.getHookType() }.getOrNull() ?: return@withContext null
            when (rawType) {
                "Manual" -> manualHookText
                "Tracepoint" -> tracepointHookText
                else -> rawType.ifBlank { unknownHookText }
            }
        }
    }.value
}
