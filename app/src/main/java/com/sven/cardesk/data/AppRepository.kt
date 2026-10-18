package com.sven.cardesk.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class AppRepository(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager

    @Suppress("DEPRECATION")
    suspend fun loadLaunchableApps(): List<AppInfo> = withContext(Dispatchers.IO) {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(intent, 0)

        resolved
            .asSequence()
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                AppInfo(
                    packageName = info.activityInfo.packageName,
                    label = info.loadLabel(packageManager).toString().ifBlank {
                        info.activityInfo.packageName
                    },
                    icon = info.loadIcon(packageManager)
                )
            }
            .sortedWith { first, second ->
                first.label.lowercase(Locale.getDefault())
                    .compareTo(second.label.lowercase(Locale.getDefault()))
            }
            .toList()
    }
}
