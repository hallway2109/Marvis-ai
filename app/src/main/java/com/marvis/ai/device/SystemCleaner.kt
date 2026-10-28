package com.marvis.ai.device

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * "Clean house protocol": asks Android to close the background processes of the apps you installed.
 *
 * What Android allows: any app may call killBackgroundProcesses() for other apps. It only ends BACKGROUND processes;
 * apps playing music, navigating or on a call (foreground services) are left alone, and apps Android wants running
 * (or that restart themselves) come back. Marvis can't list other apps' processes, so it asks for every installed
 * app except those it protects. The reported figure is the real change in free memory, which is often small on
 * modern phones because Android already manages memory well.
 */
class SystemCleaner(private val ctx: Context) {

    data class Result(val asked: Int, val freedMb: Long) {
        fun summary(): String = when {
            freedMb >= 50 -> "I cleared background processes for $asked apps and freed about $freedMb megabytes."
            freedMb > 0 -> "I cleared background processes for $asked apps and freed about $freedMb megabytes. Memory was already in good shape."
            else -> "I cleared background processes for $asked apps. Memory was already in good shape, so there was little to free."
        }
    }

    suspend fun clean(): Result = withContext(Dispatchers.Default) {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val pm = ctx.packageManager
        val before = available(am)
        val keep = protectedPackages()

        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        var asked = 0
        for (ri in launchable.distinctBy { it.activityInfo.packageName }) {
            val pkg = ri.activityInfo.packageName
            if (pkg in keep) continue
            val flags = ri.activityInfo.applicationInfo.flags
            val pureSystem = flags and ApplicationInfo.FLAG_SYSTEM != 0 && flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            if (pureSystem) continue
            if (runCatching { am.killBackgroundProcesses(pkg) }.isSuccess) asked++
        }
        delay(1200)                                   // let the system reclaim the memory before measuring
        val freed = ((available(am) - before) / (1024L * 1024L)).coerceAtLeast(0L)
        Result(asked, freed)
    }

    private fun available(am: ActivityManager): Long {
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem
    }

    /** Never touched: Marvis, the launcher, the keyboard, the app on screen, system UI. */
    private fun protectedPackages(): Set<String> {
        val pm = ctx.packageManager
        val keep = HashSet<String>()
        keep += ctx.packageName
        keep += "com.android.systemui"
        MarvisAccessibilityService.currentPackage?.let { keep += it }
        pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            ?.activityInfo?.packageName?.let { keep += it }
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.let { ComponentName.unflattenFromString(it)?.packageName }?.let { keep += it }
        return keep
    }
}
