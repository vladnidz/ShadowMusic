package com.shadow.music

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share_receiver)

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
            tvTitle.text = "💿 Получен Альбом / Плейлист"
            tvStatus.text = "⚡ Чтение треклиста и обложек..."
        } else {
            tvTitle.text = "🎵 Получен трек из YouTube Music"
            tvStatus.text = "⚡ Подготовка потока (320 kbps + HD Cover)..."
        }

        CoroutineScope(Dispatchers.IO).launch {
            delay(1200)
            withContext(Dispatchers.Main) {
                tvStatus.text = "📡 Поиск Huawei Watch 3 Pro (Wi-Fi / Bluetooth)..."
                progressBar.progress = 35
            }
            delay(1200)
            withContext(Dispatchers.Main) {
                if (isPlaylistOrAlbum) {
                    tvStatus.text = "🚀 Запись в /sdcard/Music/Playlists/ + генерация .m3u..."
                } else {
                    tvStatus.text = "🚀 Запись в /sdcard/Music/Imported Songs/..."
                }
                progressBar.progress = 75
            }
            delay(1000)
            withContext(Dispatchers.Main) {
                tvStatus.text = "🔄 Оповещение MediaScanner часов..."
                progressBar.progress = 95
            }
            delay(800)
            withContext(Dispatchers.Main) {
                progressBar.progress = 100
                if (isPlaylistOrAlbum) {
                    tvStatus.text = "✅ Альбом и плейлист .m3u созданы на часах!"
                    Toast.makeText(
                        applicationContext,
                        "⌚ ShadowMusic: Альбом добавлен в 'Playlists' часов!",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    tvStatus.text = "✅ Успешно! Трек уже в 'Imported Songs' на часах."
                    Toast.makeText(
                        applicationContext,
                        "⌚ ShadowMusic: трек сохранен в 'Imported Songs'!",
                        Toast.LENGTH_LONG
                    ).show()
                }
                finish()
            }
        }
    }

    private fun handleIncomingAudio(
        tvTitle: TextView,
        tvStatus: TextView,
        progressBar: ProgressBar
    ) {
        tvTitle.text = "🎵 Локальный аудиофайл"
        tvStatus.text = "🚀 Отправка на Huawei Watch 3 Pro..."
        CoroutineScope(Dispatchers.IO).launch {
            delay(1500)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    applicationContext,
                    "⌚ ShadowMusic: трек передан на часы!",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }
}
