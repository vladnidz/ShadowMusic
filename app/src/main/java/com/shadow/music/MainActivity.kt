package com.shadow.music

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val tvStatus = findViewById<TextView>(R.id.tvWatchStatus)
        val btnTest = findViewById<Button>(R.id.btnTestWatch)

        btnTest.setOnClickListener {
            tvStatus.text = "🟢 Подключено: Huawei Watch 3 Pro (Ready)"
        }
    }
}
