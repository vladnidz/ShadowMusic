package com.shadow.music

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvDetails: TextView
    private lateinit var btnTest: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvWatchStatus)
        tvDetails = findViewById(R.id.tvWatchDetails)
        btnTest = findViewById(R.id.btnTestWatch)

        btnTest.setOnClickListener {
            checkRealHardwareStatus()
        }

        checkRealHardwareStatus()
    }

    @SuppressLint("MissingPermission")
    private fun checkRealHardwareStatus() {
        tvStatus.text = "⏳ Проверка радиомодулей..."
        tvDetails.text = "Опрос Bluetooth и Wi-Fi..."

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val bluetoothAdapter = bluetoothManager?.adapter

        val isBtEnabled = bluetoothAdapter?.isEnabled == true

        var matchedDevice: BluetoothDevice? = null
        if (isBtEnabled) {
            try {
                val bonded = bluetoothAdapter?.bondedDevices
                if (bonded != null) {
                    for (dev in bonded) {
                        val name = dev.name ?: ""
                        if (name.contains("HUAWEI", ignoreCase = true) ||
                            name.contains("Watch", ignoreCase = true) ||
                            name.contains("HONOR", ignoreCase = true)
                        ) {
                            matchedDevice = dev
                            break
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        CoroutineScope(Dispatchers.IO).launch {
            // Real probe of watch Wi-Fi socket
            var isWifiAdbReachable = false
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress("192.168.1.116", 5555), 600)
                socket.close()
                isWifiAdbReachable = true
            } catch (e: Exception) {}

            withContext(Dispatchers.Main) {
                if (!isBtEnabled && !isWifiAdbReachable) {
                    tvStatus.text = "🔴 Часы НЕ подключены"
                    tvDetails.text = "Bluetooth выключен на телефоне, Wi-Fi недоступен"
                    tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                } else if (isWifiAdbReachable) {
                    tvStatus.text = "🟢 Подключено (Домашний Wi-Fi)"
                    tvDetails.text = "Канал: Прямой высокоскоростной Wi-Fi (192.168.1.116)"
                    tvStatus.setTextColor(getColor(R.color.neon_green))
                } else if (matchedDevice != null) {
                    tvStatus.text = "🟡 Часы сопряжены (Bluetooth)"
                    tvDetails.text = "Устройство: ${matchedDevice.name}. Вне домашнего Wi-Fi"
                    tvStatus.setTextColor(getColor(R.color.status_pending))
                } else {
                    tvStatus.text = "🔴 Часы не найдены"
                    tvDetails.text = "Включите Bluetooth на телефоне или разбудите часы"
                    tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                }
            }
        }
    }
}
