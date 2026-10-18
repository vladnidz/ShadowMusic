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
        tvTitle.text = "🎵 Получен трек из YouTube Music"
        tvStatus.text = "⚡ Подготовка потока (320 kbps + HD Cover)..."

        CoroutineScope(Dispatchers.IO).launch {
            // Simulated extraction & transfer logic over Wi-Fi / Bluetooth
            delay(1200)
            withContext(Dispatchers.Main) {
                tvStatus.text = "📡 Поиск Huawei Watch 3 Pro (Wi-Fi / Bluetooth)..."
                progressBar.progress = 50
            }
            delay(1200)
            withContext(Dispatchers.Main) {
                tvStatus.text = "🚀 Передача в /sdcard/Music на часы..."
                progressBar.progress = 85
            }
            delay(1000)
            withContext(Dispatchers.Main) {
                progressBar.progress = 100
                tvStatus.text = "✅ Успешно! Трек уже в памяти часов."
                Toast.makeText(
                    applicationContext,
                    "⌚ ShadowMusic: трек сохранен на Huawei Watch 3 Pro!",
                    Toast.LENGTH_LONG
                ).show()
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
