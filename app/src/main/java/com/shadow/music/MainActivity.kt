package com.shadow.music

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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
    private fun isDeviceConnected(device: BluetoothDevice, manager: BluetoothManager?): Boolean {
        // 1. Проверка GATT подключения (BLE)
        if (manager != null) {
            try {
                val gattState = manager.getConnectionState(device, BluetoothProfile.GATT)
                if (gattState == BluetoothProfile.STATE_CONNECTED) return true
            } catch (e: Exception) {}
        }

        // 2. Системный вызов isConnected() на уровне BluetoothDevice (ACL link)
        try {
            val method = device.javaClass.getMethod("isConnected")
            val connected = method.invoke(device) as? Boolean
            if (connected == true) return true
        } catch (e: Exception) {}

        return false
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
            // Реальная проверка доступности часов по Wi-Fi
            var reachedIp: String? = null
            for (ip in listOf("192.168.1.116", "192.168.0.116", "192.168.1.242", "192.168.0.242")) {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress(ip, 5555), 350)
                    socket.close()
                    reachedIp = ip
                    break
                } catch (e: Exception) {}
            }

            val isBtConnected = matchedDevice != null && isDeviceConnected(matchedDevice, bluetoothManager)

            withContext(Dispatchers.Main) {
                if (reachedIp != null) {
                    tvStatus.text = "🟢 Подключено (Домашний Wi-Fi)"
                    tvDetails.text = "Канал: Прямой высокоскоростной Wi-Fi ($reachedIp)\nПередача в 1 клик без запросов"
                    tvStatus.setTextColor(getColor(R.color.neon_green))
                } else if (isBtConnected) {
                    tvStatus.text = "🔵 Подключено (Bluetooth)"
                    tvDetails.text = "Устройство: ${matchedDevice?.name}. Часы рядом на связи"
                    tvStatus.setTextColor(getColor(R.color.neon_green))
                } else if (matchedDevice != null) {
                    tvStatus.text = "🔴 Часы не на связи (Оффлайн)"
                    tvDetails.text = "Устройство ${matchedDevice.name} сопряжено в памяти телефона, но находится вне зоны действия или выключено"
                    tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                } else if (!isBtEnabled) {
                    tvStatus.text = "🔴 Bluetooth выключен"
                    tvDetails.text = "Включите Bluetooth на телефоне"
                    tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                } else {
                    tvStatus.text = "🔴 Часы не найдены"
                    tvDetails.text = "Сопрягите часы Huawei в настройках телефона"
                    tvStatus.setTextColor(getColor(android.R.color.holo_red_light))
                }
            }
        }
    }
}
