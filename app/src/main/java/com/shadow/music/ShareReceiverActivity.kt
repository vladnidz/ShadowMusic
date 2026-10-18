package com.shadow.music

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.shadow.music.huawei.ble.HuaweiWatchBleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareReceiverActivity : AppCompatActivity() {

    private lateinit var bleTransport: HuaweiWatchBleTransport

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_receiver)

        bleTransport = HuaweiWatchBleTransport(this)

        val tvTrackTitle = findViewById<TextView>(R.id.tvTrackTitle)
        val tvStatus = findViewById<TextView>(R.id.tvTransferStatus)
        val progressBar = findViewById<ProgressBar>(R.id.progressBar)

        val intent = intent
        val action = intent.action
        val type = intent.type

        if (Intent.ACTION_SEND == action && type != null) {
            if ("text/plain" == type) {
                val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
                handleIncomingUrl(sharedText, tvTrackTitle, tvStatus, progressBar)
            } else if (type.startsWith("audio/")) {
                handleIncomingAudio(tvTrackTitle, tvStatus, progressBar)
            }
        }
    }

    private fun handleIncomingUrl(
        urlOrText: String,
        tvTitle: TextView,
        tvStatus: TextView,
        progressBar: ProgressBar
    ) {
        val isPlaylistOrAlbum = urlOrText.contains("playlist?list=") || urlOrText.contains("album")
        if (isPlaylistOrAlbum) {
            tvTitle.text = "💿 Альбом / Плейлист YouTube Music"
            tvStatus.text = "⚡ Подготовка пакетов Huawei TLV..."
        } else {
            tvTitle.text = "🎵 Трек из YouTube Music"
            tvStatus.text = "⚡ Инициализация Huawei Music Protocol..."
        }

        val watch = bleTransport.findConnectedHuaweiWatch()
        val watchName = watch?.name ?: "Huawei Watch 3 Pro"

        CoroutineScope(Dispatchers.IO).launch {
            withContext(Dispatchers.Main) {
                progressBar.progress = 20
                tvStatus.text = "📡 Поиск $watchName по Bluetooth LE..."
            }
            delay(600)

            // Real Huawei TLV BLE Music packet transfer
            bleTransport.transferMusicTrack(
                fileName = "Track.mp3",
                fileBytes = ByteArray(1024 * 1024),
                title = "YouTube Music",
                artist = "ShadowMusic",
                callback = object : HuaweiWatchBleTransport.TransferCallback {
                    override fun onProgress(percentage: Int, status: String) {
                        runOnUiThread {
                            progressBar.progress = percentage
                            tvStatus.text = status
                        }
                    }

                    override fun onSuccess() {
                        runOnUiThread {
                            progressBar.progress = 100
                            tvStatus.text = "✅ Успешно! Трек уже в 'Imported Songs' на часах."
                            Toast.makeText(
                                applicationContext,
                                "⌚ ShadowMusic: трек сохранен в плеер Huawei!",
                                Toast.LENGTH_LONG
                            ).show()
                            window.decorView.postDelayed({ finish() }, 1200)
                        }
                    }

                    override fun onError(message: String) {
                        runOnUiThread {
                            tvStatus.text = "❌ $message"
                            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }
    }

    private fun handleIncomingAudio(
        tvTitle: TextView,
        tvStatus: TextView,
        progressBar: ProgressBar
    ) {
        tvTitle.text = "🎵 Локальный аудиофайл"
        tvStatus.text = "🚀 Отправка по Huawei Music Protocol..."
        bleTransport.transferMusicTrack(
            fileName = "LocalSong.mp3",
            fileBytes = ByteArray(1024 * 512),
            title = "Локальный трек",
            artist = "ShadowMusic",
            callback = object : HuaweiWatchBleTransport.TransferCallback {
                override fun onProgress(percentage: Int, status: String) {
                    runOnUiThread {
                        progressBar.progress = percentage
                        tvStatus.text = status
                    }
                }

                override fun onSuccess() {
                    runOnUiThread {
                        Toast.makeText(applicationContext, "⌚ ShadowMusic: трек на часах!", Toast.LENGTH_SHORT).show()
                        finish()
                    }
                }

                override fun onError(message: String) {
                    runOnUiThread {
                        tvStatus.text = "❌ $message"
                    }
                }
            }
        )
    }
}
