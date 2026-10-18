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
import java.io.File

class ShareReceiverActivity : AppCompatActivity() {

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
                withContext(Dispatchers.Main) { progressBar.progress = 10; tvStatus.text = "🔍 Поиск часов Huawei..." }

                val watch = findHuaweiWatch()
                    ?: throw Exception("Часы Huawei не найдены в сопряжённых устройствах.\nВключите Bluetooth и убедитесь что часы сопряжены.")

                withContext(Dispatchers.Main) { progressBar.progress = 20; tvStatus.text = "✅ ${watch.name} найдены\n⬇️ Скачиваю MP3..." }

                val outDir = File(externalCacheDir ?: cacheDir, "shadowmusic").also { it.mkdirs() }
                val (mp3File, trackTitle, trackArtist) = downloadTrack(url, outDir) { prog, msg ->
                    runOnUiThread { progressBar.progress = 20 + (prog * 0.5).toInt(); tvStatus.text = msg }
                }

                withContext(Dispatchers.Main) {
                    progressBar.progress = 75
                    tvTitle.text = "🎵 $trackTitle — $trackArtist"
                    tvStatus.text = "📤 Отправка на часы по Bluetooth OPP..."
                    sendViaBtOpp(watch, mp3File)
                    progressBar.progress = 100
                    tvStatus.text = "✅ Отправлено! Прими запрос на экране часов."
                    Toast.makeText(applicationContext, "⌚ Прими передачу на часах!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { progressBar.progress = 0; tvStatus.text = "❌ ${e.message}" }
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

    private fun downloadTrack(url: String, outDir: File, onProgress: (Int, String) -> Unit): Triple<File, String, String> {
        onProgress(5, "⬇️ Подключение к YouTube Music...")
        val proc = ProcessBuilder(
            "yt-dlp", "--extract-audio", "--audio-format", "mp3",
            "--audio-quality", "0", "--embed-thumbnail", "--add-metadata",
            "--no-playlist", "-o", File(outDir, "%(title)s.%(ext)s").absolutePath, url
        ).redirectErrorStream(true).directory(outDir).start()

        proc.inputStream.bufferedReader().forEachLine { line ->
            val pct = when {
                line.contains("[download]") && line.contains("%") ->
                    Regex("(\\d+\\.?\\d*)%").find(line)?.groupValues?.get(1)?.toFloatOrNull()?.toInt() ?: return@forEachLine
                line.contains("[ExtractAudio]") -> 88
                line.contains("[EmbedThumbnail]") -> 95
                else -> return@forEachLine
            }
            onProgress(pct, line.trim().take(80))
        }

        if (proc.waitFor() != 0) throw Exception("yt-dlp не установлен на устройстве или ошибка сети.")
        val mp3 = outDir.listFiles { f -> f.extension == "mp3" }?.maxByOrNull { it.lastModified() }
            ?: throw Exception("MP3 не найден после скачивания")
        val name = mp3.nameWithoutExtension
        val (artist, title) = if (" - " in name) name.split(" - ", limit = 2).let { it[0].trim() to it[1].trim() } else "Unknown" to name
        onProgress(100, "✅ Скачано: ${mp3.name}")
        return Triple(mp3, title, artist)
    }

    @SuppressLint("MissingPermission")
    private fun sendViaBtOpp(device: BluetoothDevice, file: File) {
        val uri: Uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        startActivity(Intent("android.btopp.intent.action.SEND_FILE").apply {
            putExtra("android.btopp.intent.extra.DEVICE_ADDRESS", device.address)
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "audio/mpeg"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
    }
}
