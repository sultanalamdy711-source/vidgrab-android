package com.vidgrab.app

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private lateinit var searchInput: EditText
    private lateinit var recyclerView: RecyclerView
    private lateinit var playerLayout: View
    private lateinit var playerView: PlayerView
    private lateinit var videoTitle: TextView
    
    private var player: ExoPlayer? = null
    private var currentVideoUrl: String? = null

    @Volatile private var ready = false
    private val uiScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        searchInput = findViewById(R.id.searchInput)
        recyclerView = findViewById(R.id.recyclerView)
        playerLayout = findViewById(R.id.playerLayout)
        playerView = findViewById(R.id.playerView)
        videoTitle = findViewById(R.id.videoTitle)

        recyclerView.layoutManager = LinearLayoutManager(this)

        findViewById<Button>(R.id.btnSearch).setOnClickListener { performSearch() }
        findViewById<Button>(R.id.btnBack).setOnClickListener { closePlayer() }
        findViewById<Button>(R.id.btnDownloadVideo).setOnClickListener { startDownload(false) }
        findViewById<Button>(R.id.btnDownloadAudio).setOnClickListener { startDownload(true) }

        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performSearch()
                true
            } else false
        }

        initEngine()
    }

    private fun initEngine() {
        Toast.makeText(this, "جارٍ تجهيز المحرك...", Toast.LENGTH_SHORT).show()
        Thread {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                ready = true
                uiScope.launch { Toast.makeText(this@MainActivity, "المحرك جاهز!", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                uiScope.launch { Toast.makeText(this@MainActivity, "فشل التجهيز: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun performSearch() {
        val query = searchInput.text.toString().trim()
        if (query.isEmpty()) return
        if (!ready) {
            Toast.makeText(this, "المحرك لم يجهز بعد...", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(this, "جارٍ البحث...", Toast.LENGTH_SHORT).show()
        uiScope.launch(Dispatchers.IO) {
            try {
                val req = YoutubeDLRequest("ytsearch10:$query")
                req.addOption("--flat-playlist")
                req.addOption("--print", "%(title)s|%(url)s|%(thumbnail)s|%(duration_string)s")
                
                val result = YoutubeDL.getInstance().execute(req)
                val lines = result.out.lines().filter { it.contains("|") }
                
                val results = mutableListOf<VideoItem>()
                lines.forEach { line ->
                    val parts = line.split("|")
                    if (parts.size >= 4) {
                        results.add(VideoItem(parts[0], parts[1], parts[2], parts[3]))
                    }
                }
                
                withContext(Dispatchers.Main) {
                    recyclerView.adapter = VideoAdapter(results) { item ->
                        playVideo(item)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "فشل البحث: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun playVideo(item: VideoItem) {
        currentVideoUrl = item.url
        videoTitle.text = item.title
        playerLayout.visibility = View.VISIBLE
        
        Toast.makeText(this, "جارٍ تشغيل الفيديو...", Toast.LENGTH_SHORT).show()
        uiScope.launch(Dispatchers.IO) {
            try {
                val req = YoutubeDLRequest(item.url)
                req.addOption("-f", "best[height<=480]") // جودة متوسطة للتشغيل السريع
                req.addOption("--get-url")
                val directUrl = YoutubeDL.getInstance().execute(req).out.trim().lines().first()
                
                withContext(Dispatchers.Main) {
                    player = ExoPlayer.Builder(this@MainActivity).build().also { exo ->
                        playerView.player = exo
                        exo.setMediaItem(MediaItem.fromUri(Uri.parse(directUrl)))
                        exo.prepare()
                        exo.play()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "فشل التشغيل: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun closePlayer() {
        player?.release()
        player = null
        playerLayout.visibility = View.GONE
    }

    private fun startDownload(audio: Boolean) {
        val url = currentVideoUrl ?: return
        Toast.makeText(this, if (audio) "جارٍ تنزيل الصوت..." else "جارٍ تنزيل الفيديو...", Toast.LENGTH_SHORT).show()
        
        uiScope.launch(Dispatchers.IO) {
            val tmp = File(cacheDir, "dl_" + UUID.randomUUID()).apply { mkdirs() }
            try {
                val req = YoutubeDLRequest(url)
                req.addOption("--no-playlist")
                req.addOption("-o", File(tmp, "%(title).80s.%(ext)s").absolutePath)
                if (audio) {
                    req.addOption("-x")
                    req.addOption("--audio-format", "mp3")
                } else {
                    req.addOption("-f", "bv*[height<=720]+ba/b[height<=720]/b")
                    req.addOption("--merge-output-format", "mp4")
                }
                
                YoutubeDL.getInstance().execute(req) { _, _, _ -> }

                val out = tmp.listFiles()?.maxByOrNull { it.length() }
                if (out != null && saveToDownloads(out)) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "تم الحفظ في Download/VidGrab ✓", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "فشل التنزيل: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                tmp.deleteRecursively()
            }
        }
    }

    private fun saveToDownloads(f: File): Boolean {
        val mime = when (f.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VidGrab")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        contentResolver.openOutputStream(uri)?.use { o -> f.inputStream().use { it.copyTo(o) } }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        uiScope.cancel()
    }
}
