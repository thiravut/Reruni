package com.rerun.tiktokrerun

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object VideoDownloader {

    private val client = OkHttpClient()

    /** Returns a local File holding the downloaded video. Cached by URL hash. */
    fun fetch(context: Context, url: String): File {
        val cacheDir = File(context.cacheDir, "videos").also { it.mkdirs() }
        val key = sha1(url) + extOf(url)
        val out = File(cacheDir, key)
        if (out.exists() && out.length() > 0) return out

        val tmp = File(cacheDir, "$key.part")
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("empty body")
            body.byteStream().use { input ->
                FileOutputStream(tmp).use { fout ->
                    input.copyTo(fout)
                }
            }
        }
        tmp.renameTo(out)
        return out
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun extOf(url: String): String {
        val q = url.indexOf('?').let { if (it < 0) url.length else it }
        val path = url.substring(0, q)
        val dot = path.lastIndexOf('.')
        return if (dot < 0 || dot < path.length - 5) ".mp4" else path.substring(dot)
    }
}
