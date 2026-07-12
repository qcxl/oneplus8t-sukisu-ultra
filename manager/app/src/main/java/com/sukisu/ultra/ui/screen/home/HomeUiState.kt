package com.sukisu.ultra.ui.screen.home

import androidx.compose.runtime.Immutable
import com.sukisu.ultra.KernelVersion
import com.sukisu.ultra.ui.util.module.LatestVersionInfo

@Immutable
data class HomeUiState(
    val kernelVersion: KernelVersion = KernelVersion(0, 0, 0),
    val ksuVersion: Int? = null,
    val managerUAPIVersion: Int = 0,
    val kernelUAPIVersion: Int? = null,
    val lkmMode: Boolean? = null,
    val isManager: Boolean = false,
    val isManagerPrBuild: Boolean = false,
    val isKernelPrBuild: Boolean = false,
    val requiresNewKernel: Boolean = false,
    val uapiMismatch: Boolean = false,
    val isRootAvailable: Boolean = false,
    val isSafeMode: Boolean = false,
    val isLateLoadMode: Boolean = false,
    val checkUpdateEnabled: Boolean = false,
    val latestVersionInfo: LatestVersionInfo = LatestVersionInfo(),
    val currentManagerVersionCode: Long = 0L,
    val superuserCount: Int = 0,
    val moduleCount: Int = 0,
    val systemInfo: SystemInfo = SystemInfo(),
    val showFullStatus: Boolean = true,
) {
    val isSELinuxPermissive: Boolean
        get() = systemInfo.selinuxStatus == "Permissive"

    val isFullFeatured: Boolean
        get() = isManager && !requiresNewKernel && isRootAvailable

    val showRequireKernelWarning: Boolean
        get() = isManager && requiresNewKernel && lkmMode == true

    val showUAPIMisMatchWarning: Boolean
        get() = isManager && showRequireKernelWarning && uapiMismatch

    val showRootWarning: Boolean
        get() = ksuVersion != null && !isRootAvailable

    val showManagerPrBuildWarning: Boolean
        get() = isManager && isManagerPrBuild

    val showKernelPrBuildWarning: Boolean
        get() = isManager && !isManagerPrBuild && isKernelPrBuild

    val showVersionMismatchWarning: Boolean
        get() = false // cosmetic; real compat check is showRequireKernelWarning

    val hasUpdate: Boolean
        get() = latestVersionInfo.versionCode > currentManagerVersionCode
}

@Immutable
data class HomeActions(
    val onInstallClick: () -> Unit,
    val onSuperuserClick: () -> Unit,
    val onModuleClick: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onJailbreakClick: () -> Unit = {},
)
