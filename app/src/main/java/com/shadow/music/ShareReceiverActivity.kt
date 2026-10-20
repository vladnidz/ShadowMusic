package com.shadow.music

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Фоновый бесшовный перехват: сервис выполняет скачивание и загрузку в фоне
        val action = intent?.action
        val type = intent?.type

        if (Intent.ACTION_SEND == action && type != null) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val url = extractUrl(sharedText)

            if (!url.isNullOrBlank()) {
                TransferService.start(this, url)
                Toast.makeText(applicationContext, "⚡ ShadowMusic: загрузка трека в фоне...", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(applicationContext, "❌ Ссылка на трек не найдена", Toast.LENGTH_SHORT).show()
            }
        }

        finish()
    }

    private fun extractUrl(text: String): String? {
        val regex = Regex("https?://[^\\s]+")
        return regex.find(text)?.value
    }
}
