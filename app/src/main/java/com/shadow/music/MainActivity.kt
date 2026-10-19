package com.shadow.music

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
            checkAndRequestPermissions()
            checkRealHardwareStatus()
        }

        checkAndRequestPermissions()
        checkRealHardwareStatus()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), 1001)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
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
                    tvDetails.text = "Устройство: ${matchedDevice.name}. Готово к передаче по Bluetooth"
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
