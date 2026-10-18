package com.shadow.music

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import java.io.File
import java.io.FileOutputStream

class ShareReceiverActivity : AppCompatActivity() {

    private val http = OkHttpClient()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_share_receiver)

        val tvTitle = findViewById<TextView>(R.id.tvTrackTitle)
        val tvStatus = findViewById<TextView>(R.id.tvTransferStatus)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)

        val action = intent?.action
        val type = intent?.type

        if (Intent.ACTION_SEND == action && type == "text/plain") {
            val url = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            if (url.isBlank()) { tvStatus.text = "❌ Пустая ссылка"; return }
            handleShare(url, tvTitle, tvStatus, progressBar)
        } else {
            tvStatus.text = "❌ Поделитесь ссылкой из YouTube Music"
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleShare(url: String, tvTitle: TextView, tvStatus: TextView, progressBar: ProgressBar) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Find watch
                withContext(Dispatchers.Main) { progressBar.progress = 5; tvStatus.text = "🔍 Поиск часов Huawei..." }
                val watch = findHuaweiWatch()
                    ?: throw Exception("Часы Huawei не найдены.\nВключите Bluetooth и убедитесь что часы сопряжены.")

                withContext(Dispatchers.Main) { progressBar.progress = 15; tvStatus.text = "✅ ${watch.name}\n🔍 Извлечение потока YouTube..." }

                // 2. Extract stream URL via NewPipe Extractor (pure Java, works on Android)
                NewPipe.init(object : org.schabi.newpipe.extractor.downloader.Downloader() {
                    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): org.schabi.newpipe.extractor.downloader.Response {
                        val req = Request.Builder().url(request.url())
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                            .header("Accept-Language", "en-US,en;q=0.9")
                            .apply { request.headers().forEach { (k, v) -> v.forEach { addHeader(k, it) } } }
                            .build()
                        val resp = http.newCall(req).execute()
                        return org.schabi.newpipe.extractor.downloader.Response(
                            resp.code, resp.message,
                            resp.headers.toMultimap(),
                            resp.body?.string() ?: "",
                            resp.request.url.toString()
                        )
                    }
                })

                val extractor: StreamExtractor = ServiceList.YouTube.getStreamExtractor(url)
                extractor.fetchPage()

                val trackTitle = extractor.name
                val trackArtist = try { extractor.uploaderName } catch (e: Exception) { "YouTube Music" }

                withContext(Dispatchers.Main) {
                    progressBar.progress = 35
                    tvTitle.text = "🎵 $trackTitle"
                    tvStatus.text = "⬇️ Скачиваю аудио..."
                }

                // Pick best audio stream
                val audioStream: AudioStream = extractor.audioStreams
                    .filter { it.format?.mimeType?.contains("audio") == true }
                    .maxByOrNull { it.averageBitrate }
                    ?: throw Exception("Аудио поток не найден")

                val audioUrl = audioStream.content

                // 3. Download audio to cache
                val outDir = File(externalCacheDir ?: cacheDir, "shadowmusic").also { it.mkdirs() }
                val safeTitle = trackTitle.replace(Regex("[^\\w\\s-]"), "").trim().take(80)
                val mp3File = File(outDir, "$safeTitle.m4a") // YouTube sends m4a/webm

                val dlReq = Request.Builder().url(audioUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://music.youtube.com/")
                    .build()
                val dlResp = http.newCall(dlReq).execute()
                val body = dlResp.body ?: throw Exception("Пустой ответ от YouTube")
                val total = body.contentLength()
                var downloaded = 0L

                FileOutputStream(mp3File).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(8192)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            out.write(buf, 0, n)
                            downloaded += n
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt()
                                runOnUiThread { progressBar.progress = 35 + (pct * 0.4).toInt() }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progressBar.progress = 80
                    tvStatus.text = "📤 Отправка на часы по Bluetooth OPP..."
                }

                // 4. Send via Bluetooth OPP
                withContext(Dispatchers.Main) {
                    sendViaBtOpp(watch, mp3File, trackTitle, trackArtist)
                    progressBar.progress = 100
                    tvStatus.text = "✅ Отправлено!\nПрими запрос на экране часов."
                    Toast.makeText(applicationContext, "⌚ Прими передачу на часах!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.progress = 0
                    tvStatus.text = "❌ ${e.message}"
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findHuaweiWatch(): BluetoothDevice? {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        return adapter.bondedDevices?.firstOrNull { dev ->
            val n = dev.name ?: ""
            n.contains("HUAWEI", ignoreCase = true) || n.contains("Watch", ignoreCase = true) || n.contains("HONOR", ignoreCase = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendViaBtOpp(device: BluetoothDevice, file: File, title: String, artist: String) {
        val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        startActivity(Intent("android.btopp.intent.action.SEND_FILE").apply {
            putExtra("android.btopp.intent.extra.DEVICE_ADDRESS", device.address)
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}
