package com.rerun.tiktokrerun.script

import android.content.Context
import android.util.Log
import androidx.annotation.RawRes
import com.rerun.tiktokrerun.AppPrefs
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Three-tier resolver for automation scripts.
 *
 * Lookup order in [getScript]:
 *   1. **Disk cache** (`cacheDir/scripts/{name}.json`) — written after a
 *      successful fetch, survives process restarts.
 *   2. **Bundled baseline** ([R.raw.script_*]) — what shipped with the APK;
 *      always present, used when nothing has been fetched yet.
 *
 * [refresh] hits `${serverUrl}/api/scripts/{name}` and writes the response
 * over the cache when it parses cleanly. Call it from a non-blocking
 * context (e.g. MainActivity.onCreate launching a coroutine) — when it
 * fails, the next call to [getScript] just returns the older cache or
 * baseline.
 *
 * No version negotiation here: the server is treated as the source of
 * truth, the client never refuses an older version. If we end up needing
 * that we'll lift `version` out of the JSON and persist it in prefs.
 */
class ScriptStore(private val context: Context) {

    private val cacheDir: File = File(context.cacheDir, "scripts").apply { mkdirs() }

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(8, TimeUnit.SECONDS)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Returns the script with the given [name], preferring the disk cache
     * over the bundled fallback. Throws if neither is available.
     */
    fun getScript(name: String, @RawRes fallbackResId: Int): JSONObject {
        val cached = readCacheFile(name)
        if (cached != null) {
            Log.i(TAG, "getScript($name): served from cache (${cached.length} chars)")
            return runCatching { JSONObject(cached) }.getOrElse {
                Log.w(TAG, "getScript($name): cache parse failed, falling back to bundle", it)
                readBundle(fallbackResId)
            }
        }
        Log.i(TAG, "getScript($name): no cache, served from bundle")
        return readBundle(fallbackResId)
    }

    /**
     * Fetches the named script from the server and overwrites the cache on
     * success. Quiet on failure — caller is expected to be best-effort.
     */
    fun refresh(name: String) {
        val serverUrl = AppPrefs(context).serverUrl.trimEnd('/')
        if (serverUrl.isEmpty()) {
            Log.i(TAG, "refresh($name): no serverUrl, skipping")
            return
        }
        val url = "$serverUrl/api/scripts/$name"
        val req = Request.Builder().url(url).get().build()
        try {
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "refresh($name): HTTP ${resp.code} from $url")
                    return
                }
                val body = resp.body?.string()
                if (body.isNullOrEmpty()) {
                    Log.w(TAG, "refresh($name): empty body from $url")
                    return
                }
                // Parse before writing so we don't poison the cache with garbage.
                runCatching { JSONObject(body) }.onFailure {
                    Log.w(TAG, "refresh($name): server returned non-JSON, ignoring", it)
                    return
                }
                writeCacheFile(name, body)
                Log.i(TAG, "refresh($name): cache updated (${body.length} chars)")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "refresh($name): fetch failed", t)
        }
    }

    private fun readCacheFile(name: String): String? {
        val file = File(cacheDir, "$name.json")
        if (!file.exists() || file.length() == 0L) return null
        return try {
            file.readText()
        } catch (t: Throwable) {
            Log.w(TAG, "readCacheFile($name) failed", t); null
        }
    }

    private fun writeCacheFile(name: String, body: String) {
        val tmp = File(cacheDir, "$name.json.tmp")
        val target = File(cacheDir, "$name.json")
        try {
            tmp.writeText(body)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "writeCacheFile($name) failed", t)
        }
    }

    private fun readBundle(@RawRes resId: Int): JSONObject {
        val text = context.resources.openRawResource(resId)
            .bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    companion object {
        private const val TAG = "ScriptStore"

        /** Stable script names — server endpoints + cache files key off these. */
        const val PERSONAL_LIVE = "personal_live"
        const val SHOPPABLE_VCAM = "shoppable_vcam"
        const val END_LIVE = "end_live"
    }
}
