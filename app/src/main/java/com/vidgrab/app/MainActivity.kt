package com.vidgrab.app

import android.app.Activity
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID

class MainActivity : Activity() {

    private val INK = Color.parseColor("#12263F")
    private val MUTED = Color.parseColor("#5B6B80")
    private val LINE = Color.parseColor("#D5DDE8")
    private val BG = Color.parseColor("#EDF2F7")
    private val SIGNAL = Color.parseColor("#F5A524")
    private val ERR = Color.parseColor("#B42318")

    private lateinit var urlInput: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var btnVideo: Button
    private lateinit var btnAudio: Button

    @Volatile private var ready = false
    @Volatile private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        buildUi()

        // رابط مشارك من تطبيق آخر (يوتيوب، تيك توك، ...)
        if (intent?.action == Intent.ACTION_SEND) {
            intent.getStringExtra(Intent.EXTRA_TEXT)?.let { urlInput.setText(extractUrl(it) ?: it) }
        }
        initEngine()
    }

    // ---------- المحرك ----------
    private fun initEngine() {
        say("جارٍ تجهيز المحرك...")
        setBusy(true)
        Thread {
            try {
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
            } catch (e: Exception) {
                ui { say("فشل تجهيز المحرك: ${e.message}", true); setBusy(false) }
                return@Thread
            }
            // تحديث yt-dlp لأن المنصات تغيّر أنظمتها كثيراً (إن فشل نكمل بالنسخة المضمّنة)
            ui { say("جارٍ تحديث المحرك...") }
            try {
                YoutubeDL.getInstance().updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
            } catch (_: Exception) { }
            ready = true
            ui { say("جاهز. ألصق الرابط وابدأ."); setBusy(false) }
        }.start()
    }

    // ---------- التنزيل ----------
    private fun startDownload(audio: Boolean) {
        val url = extractUrl(urlInput.text.toString())
        if (url == null) { say("ضع رابطاً صحيحاً أولاً", true); return }
        if (!ready || busy) return

        setBusy(true)
        say(if (audio) "جارٍ تنزيل الصوت..." else "جارٍ تنزيل الفيديو...")
        Thread {
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
                YoutubeDL.getInstance().execute(req, null) { p, _, _ ->
                    ui { progress.progress = p.toInt().coerceIn(0, 100) }
                }

                val out = tmp.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }
                    ?.maxByOrNull { it.length() }
                if (out == null || !saveToDownloads(out)) {
                    ui { say("لم يُحفظ الملف، جرّب رابطاً آخر", true) }
                } else {
                    ui { say("تم الحفظ في مجلد Download/VidGrab ✓") }
                }
            } catch (e: Exception) {
                ui { say("فشل التنزيل: ${e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: "خطأ غير معروف"}", true) }
            } finally {
                tmp.deleteRecursively()
                ui { setBusy(false) }
            }
        }.start()
    }

    private fun saveToDownloads(f: File): Boolean {
        val mime = when (f.extension.lowercase()) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            else -> "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/VidGrab")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        val stream = contentResolver.openOutputStream(uri)
        if (stream == null) {
            contentResolver.delete(uri, null, null)
            return false
        }
        stream.use { o -> f.inputStream().use { it.copyTo(o) } }
        return true
    }

    private fun extractUrl(text: String): String? =
        Regex("""https?://[^\s"'<>]+""").find(text)?.value

    // ---------- الواجهة ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(28))
        }
        root.addView(TextView(this).apply {
            text = "ألصق الرابط، نزّل الفيديو"
            textSize = 28f; setTextColor(INK); setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "يعمل مع يوتيوب وتيك توك وإنستغرام وفيسبوك وغيرها. التنزيل يتم على جوالك مباشرة."
            textSize = 15f; setTextColor(MUTED)
            setPadding(0, dp(6), 0, dp(20))
        })

        urlInput = EditText(this).apply {
            hint = "ضع رابط الفيديو هنا"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            setTextColor(INK); setHintTextColor(MUTED); textSize = 16f
            background = bg(Color.WHITE, 14, LINE)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        root.addView(urlInput, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        root.addView(btn("لصق من الحافظة", false) {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
            if (t.isNullOrBlank()) say("الحافظة فارغة", true) else urlInput.setText(t.trim())
        })
        btnVideo = btn("تنزيل الفيديو (حتى 720p)", true) { startDownload(false) }
        btnAudio = btn("تنزيل الصوت فقط MP3", false) { startDownload(true) }
        root.addView(btnVideo)
        root.addView(btnAudio)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.INVISIBLE
            progressTintList = android.content.res.ColorStateList.valueOf(SIGNAL)
        }
        root.addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(18) })

        status = TextView(this).apply { textSize = 15f; setTextColor(MUTED); setPadding(0, dp(8), 0, 0) }
        root.addView(status)

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
    }

    private fun btn(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (filled) Color.WHITE else INK)
        background = bg(if (filled) INK else Color.TRANSPARENT, 12, if (filled) null else LINE)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun setBusy(b: Boolean) {
        busy = b
        if (::btnVideo.isInitialized) {
            btnVideo.isEnabled = !b; btnAudio.isEnabled = !b
            btnVideo.alpha = if (b) 0.55f else 1f; btnAudio.alpha = if (b) 0.55f else 1f
            progress.visibility = if (b) View.VISIBLE else View.INVISIBLE
            if (!b) progress.progress = 0
        }
    }

    private fun say(text: String, isErr: Boolean = false) {
        status.text = text
        status.setTextColor(if (isErr) ERR else MUTED)
    }

    private fun bg(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(2), stroke)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(block: () -> Unit) = runOnUiThread(block)
}
