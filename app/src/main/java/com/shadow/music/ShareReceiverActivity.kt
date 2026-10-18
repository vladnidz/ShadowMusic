package com.shadow.music

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ShareReceiverActivity : AppCompatActivity() {

    // Raspberry Pi server — does yt-dlp + adb push to watch
    private val SERVER_URL = "http://shadow3.local:8765"

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

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
            if (url.isBlank()) {
                tvStatus.text = "❌ Пустая ссылка"
                return
            }
            tvTitle.text = "🎵 Отправка на Raspberry Pi..."
            tvStatus.text = url.take(60) + "..."
            progressBar.progress = 10
            sendToServer(url, tvTitle, tvStatus, progressBar)
        } else {
            tvStatus.text = "❌ Поделитесь ссылкой из YouTube Music ReVanced"
        }
    }

    private fun sendToServer(
        url: String,
        tvTitle: TextView,
        tvStatus: TextView,
        progressBar: ProgressBar
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                withContext(Dispatchers.Main) {
                    progressBar.progress = 20
                    tvStatus.text = "📡 Связь с Raspberry Pi (shadow3.local)..."
                }

                val body = FormBody.Builder()
                    .add("url", url)
                    .build()

                val request = Request.Builder()
                    .url("$SERVER_URL/transfer")
                    .post(body)
                    .build()

                val response = client.newCall(request).execute()
                val responseText = response.body?.string() ?: ""

                withContext(Dispatchers.Main) {
                    if (response.isSuccessful) {
                        progressBar.progress = 100
                        tvTitle.text = "✅ Трек на часах!"
                        tvStatus.text = responseText.take(120)
                        Toast.makeText(
                            applicationContext,
                            "⌚ Готово! Открой плеер на часах",
                            Toast.LENGTH_LONG
                        ).show()
                        window.decorView.postDelayed({ finish() }, 2500)
                    } else {
                        progressBar.progress = 0
                        tvStatus.text = "❌ Ошибка сервера: ${response.code}\n$responseText"
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.progress = 0
                    tvStatus.text = "❌ Не удалось подключиться к Raspberry Pi.\n" +
                        "Убедитесь что телефон в домашней Wi-Fi сети.\n\n${e.message}"
                }
            }
        }
    }
}
