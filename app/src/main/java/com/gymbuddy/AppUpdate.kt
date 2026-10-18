package com.gymbuddy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

object AppUpdate {
    sealed class Check {
        class Update(val offer: ReleaseVersion.Offer) : Check()
        object Current : Check()
        object Failed : Check()
    }
    private const val PREFS = "app_update"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_VERSION = "version_name"
    private const val KEY_URL = "apk_url"
    private const val KEY_DISMISSED = "dismissed_version"
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val RELEASES_URL = "https://api.github.com/repos/bhwithun/Gym-Buddy/releases/latest"
    private const val APK_TYPE = "application/vnd.android.package-archive"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val waiters = mutableListOf<(ReleaseVersion.Offer?) -> Unit>()
    private var refreshInFlight = false
    private var promptedVersion: String? = null
    private var installInFlight = false

    fun offer(context: Context): ReleaseVersion.Offer? {
        val prefs = prefs(context)
        val versionName = prefs.getString(KEY_VERSION, null)?.trim().orEmpty()
        val apkUrl = prefs.getString(KEY_URL, null)?.trim().orEmpty()
        if (versionName.isEmpty() || !apkUrl.startsWith("https://")) return null
        if (!ReleaseVersion.isNewer(versionName, installedVersionName(context))) return null
        return ReleaseVersion.Offer(versionName, apkUrl)
    }

    fun dismiss(context: Context, versionName: String) {
        prefs(context).edit().putString(KEY_DISMISSED, versionName).apply()
    }

    fun refreshAsync(context: Context, onResult: (ReleaseVersion.Offer?) -> Unit) {
        val app = context.applicationContext
        val activity = context as? Activity
        fun deliver(offer: ReleaseVersion.Offer?) {
            if (activity != null) {
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) onResult(offer)
                }
            } else {
                onResult(offer)
            }
        }
        if (!isCheckDue(app)) {
            deliver(offer(app))
            return
        }
        synchronized(waiters) {
            waiters.add { result -> deliver(result) }
            if (refreshInFlight) return
            refreshInFlight = true
        }
        thread {
            try {
                fetchAndStore(app)
            } catch (_: Exception) {
            }
            val result = offer(app)
            val pending = synchronized(waiters) {
                refreshInFlight = false
                waiters.toList().also { waiters.clear() }
            }
            pending.forEach { it(result) }
        }
    }

    fun checkNow(context: Context, onResult: (Check) -> Unit) {
        val app = context.applicationContext
        thread {
            val result = try {
                if (!fetchAndStore(app)) Check.Failed
                else offer(app)?.let { Check.Update(it) } ?: Check.Current
            } catch (_: Exception) {
                Check.Failed
            }
            val activity = context as? Activity
            if (activity != null) activity.runOnUiThread { onResult(result) } else onResult(result)
        }
    }

    fun shouldPrompt(context: Context, offer: ReleaseVersion.Offer): Boolean {
        if (promptedVersion == offer.versionName) return false
        return prefs(context).getString(KEY_DISMISSED, null) != offer.versionName
    }

    fun markPrompted(offer: ReleaseVersion.Offer) {
        promptedVersion = offer.versionName
    }

    fun startInstall(activity: Activity, offer: ReleaseVersion.Offer) {
        if (installInFlight) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            Toast.makeText(activity, R.string.update_allow, Toast.LENGTH_LONG).show()
            try {
                activity.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${activity.packageName}")
                    )
                )
            } catch (_: Exception) {
                Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
            }
            return
        }
        installInFlight = true
        Toast.makeText(activity, R.string.update_downloading, Toast.LENGTH_SHORT).show()
        thread {
            val file = try {
                download(activity.applicationContext, offer)
            } catch (_: Exception) {
                null
            }
            activity.runOnUiThread {
                installInFlight = false
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                if (file == null || !downloadedApkCanUpgrade(activity, file) || !launchInstaller(activity, file)) {
                    Toast.makeText(activity, R.string.update_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun isCheckDue(context: Context): Boolean {
        val checkedAt = prefs(context).getLong(KEY_CHECKED_AT, 0L)
        return System.currentTimeMillis() - checkedAt >= DAY_MS
    }

    private fun fetchAndStore(context: Context): Boolean {
        val request = Request.Builder()
            .url(RELEASES_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "GymBuddy")
            .build()
        http.newCall(request).execute().use { response ->
            val editor = prefs(context).edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis())
            if (response.isSuccessful) {
                val parsed = ReleaseVersion.parseLatestRelease(response.body?.string().orEmpty())
                if (parsed != null && ReleaseVersion.isNewer(parsed.versionName, installedVersionName(context))) {
                    editor.putString(KEY_VERSION, parsed.versionName).putString(KEY_URL, parsed.apkUrl)
                } else {
                    editor.remove(KEY_VERSION).remove(KEY_URL)
                }
            }
            editor.apply()
            return response.isSuccessful
        }
    }

    private fun download(context: Context, offer: ReleaseVersion.Offer): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, "gym-buddy-update.apk")
        val part = File(dir, "gym-buddy-update.apk.part")
        val request = Request.Builder()
            .url(offer.apkUrl)
            .header("User-Agent", "GymBuddy")
            .header("Accept", "application/vnd.android.package-archive")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("download failed")
            val body = response.body ?: error("empty download")
            part.outputStream().use { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
        }
        val header = ByteArray(2)
        part.inputStream().use { input ->
            if (input.read(header) != 2 || header[0] != 'P'.code.toByte() || header[1] != 'K'.code.toByte()) {
                part.delete()
                error("not an apk")
            }
        }
        if (dest.exists() && !dest.delete()) error("replace failed")
        if (!part.renameTo(dest)) error("rename failed")
        return dest
    }

    private fun downloadedApkCanUpgrade(context: Context, file: File): Boolean {
        val archived = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
        if (archived.packageName != context.packageName) return false
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        return versionCode(archived) > versionCode(installed)
    }

    private fun versionCode(info: PackageInfo): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun launchInstaller(activity: Activity, file: File): Boolean {
        return try {
            val uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file
            )
            activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, APK_TYPE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun installedVersionName(context: Context): String {
        return context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
