package com.rerun.tiktokrerun

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rerun.tiktokrerun.databinding.ActivityDiagnosticsBinding
import kotlinx.coroutines.launch

/**
 * Setup + debug tooling that used to live on the home screen. These controls
 * are not part of daily broadcaster use — only needed when:
 *
 *  - Manually picking a local video to drive a screen-share dry-run without
 *    the portal/server path (handy when the API isn't reachable yet)
 *  - Dumping TikTok UI for autopilot selector tuning (debug builds)
 *
 * Reachable only from Settings; the home screen has no entry point so it
 * doesn't crowd the production primary surface.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding
    private val selectedVideos = mutableListOf<Uri>()

    private val pickVideosLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (!selectedVideos.contains(uri)) selectedVideos.add(uri)
        }
        refreshList()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.diagnosticsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.diagnosticsToolbar.setNavigationOnClickListener { finish() }

        binding.pickVideoButton.setOnClickListener {
            pickVideosLauncher.launch(arrayOf("video/*"))
        }
        binding.clearButton.setOnClickListener {
            selectedVideos.clear()
            refreshList()
        }
        binding.startButton.setOnClickListener {
            if (selectedVideos.isEmpty()) return@setOnClickListener
            startActivity(
                Intent(this, PlayerActivity::class.java).apply {
                    putParcelableArrayListExtra(
                        PlayerActivity.EXTRA_VIDEO_URIS,
                        ArrayList(selectedVideos)
                    )
                }
            )
        }

        binding.dumpUiButton.visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
        binding.dumpUiButton.setOnClickListener {
            // Delay-then-dump so user has time to switch back to TikTok. While our app
            // is in foreground, TikTok's window may be filtered out of service.windows;
            // pulling focus back to TikTok ensures it's captured.
            Toast.makeText(
                this,
                "⏳ Dump in 6s — สลับกลับไป TikTok ทันที (ไม่ต้องกดอะไรเพิ่ม)",
                Toast.LENGTH_LONG,
            ).show()
            lifecycleScope.launch {
                kotlinx.coroutines.delay(6000)
                val dump = Autopilot.dumpVisibleNodes()
                val file = java.io.File(externalCacheDir ?: cacheDir, "manual_dump.txt")
                file.writeText(dump)
                android.util.Log.i("UIDump", "─── BEGIN dump (${dump.length} chars, ${dump.lineSequence().count()} lines) ───")
                val chunkSize = 3000
                var start = 0
                var idx = 0
                while (start < dump.length) {
                    val end = (start + chunkSize).coerceAtMost(dump.length)
                    android.util.Log.i("UIDump", "[chunk $idx] " + dump.substring(start, end))
                    start = end
                    idx++
                }
                android.util.Log.i("UIDump", "─── END dump (file=${file.absolutePath}) ───")
            }
        }

        refreshList()
    }

    private fun refreshList() {
        binding.startButton.isEnabled = selectedVideos.isNotEmpty()
        binding.clearButton.isEnabled = selectedVideos.isNotEmpty()
        binding.selectedVideoText.text = if (selectedVideos.isEmpty()) {
            getString(R.string.no_video_selected)
        } else {
            val names = selectedVideos.joinToString("\n") { uri -> "• " + displayName(uri) }
            getString(R.string.selected_videos_count, selectedVideos.size) + "\n" + names
        }
    }

    private fun displayName(uri: Uri): String {
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
