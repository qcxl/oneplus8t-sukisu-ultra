package com.sukisu.ultra.data.repository

import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sukisu.ultra.Natives
import com.sukisu.ultra.data.model.AppInfo
import com.sukisu.ultra.ksuApp

class SuperUserRepositoryImpl : SuperUserRepository {

    companion object {
        private const val TAG = "SuperUserRepository"
    }

    override suspend fun getAppList(): Result<Pair<List<AppInfo>, List<Int>>> = withContext(Dispatchers.IO) {
        runCatching {
            val pm = ksuApp.packageManager
            val start = SystemClock.elapsedRealtime()

            val allPackages = pm.getInstalledPackages(0)

            val newApps = allPackages.filter {
                val ai = it.applicationInfo ?: return@filter false
                (ai.flags and ApplicationInfo.FLAG_HAS_CODE) != 0
            }.map {
                val appInfo = it.applicationInfo!!
                val profile = Natives.getAppProfile(it.packageName, appInfo.uid)
                AppInfo(
                    label = appInfo.loadLabel(pm).toString(),
                    packageInfo = it,
                    profile = profile,
                )
            }

            Log.i(TAG, "load cost: ${SystemClock.elapsedRealtime() - start}, apps: ${newApps.size}")
            Pair(newApps, listOf(0))
        }
    }

    override suspend fun refreshProfiles(currentApps: List<AppInfo>): Result<List<AppInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            if (currentApps.isEmpty()) return@runCatching emptyList()

            currentApps.map {
                val profile = Natives.getAppProfile(it.packageName, it.uid)
                it.copy(profile = profile)
            }
        }
    }
}
