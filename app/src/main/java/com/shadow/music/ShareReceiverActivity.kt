package com.shadow.music

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamExtractor
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class ShareReceiverActivity : AppCompatActivity() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private var isNewPipeInitialized = false

        @Synchronized
        fun ensureNewPipeInitialized(client: OkHttpClient) {
            if (isNewPipeInitialized) return

            NewPipe.init(object : Downloader() {
                private val USER_AGENT =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

                override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
                    val httpMethod = request.httpMethod() ?: "GET"
                    val url = request.url()
                    val dataToSend = request.dataToSend()

                    val requestBody: RequestBody? = if (dataToSend != null) {
                        RequestBody.create(null, dataToSend)
                    } else if (httpMethod.equals("POST", ignoreCase = true) || httpMethod.equals("PUT", ignoreCase = true)) {
                        RequestBody.create(null, ByteArray(0))
                    } else {
                        null
                    }

                    val builder = Request.Builder()
                        .method(httpMethod, requestBody)
                        .url(url)
                        .addHeader("User-Agent", USER_AGENT)
                        .addHeader("Accept-Language", "en-US,en;q=0.9")

                    request.headers()?.forEach { (headerName, headerValues) ->
                        builder.removeHeader(headerName)
                        headerValues.forEach { headerValue ->
                            builder.addHeader(headerName, headerValue)
                        }
                    }

                    val response = client.newCall(builder.build()).execute()
                    val responseBody = response.body?.string() ?: ""
                    val latestUrl = response.request.url.toString()

                    return Response(
                        response.code,
                        response.message,
                        response.headers.toMultimap(),
                        responseBody,
                        latestUrl
                    )
                }
            })
            isNewPipeInitialized = true
        }
    }

    private var pendingUrl: String? = null
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_share_receiver)

        tvTitle = findViewById(R.id.tvTrackTitle)
        tvStatus = findViewById(R.id.tvTransferStatus)
        progressBar = findViewById(R.id.progressBar)

        ensureNewPipeInitialized(httpClient)

        val action = intent?.action
        val type = intent?.type

        if (Intent.ACTION_SEND == action && type != null) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
            val urlMatch = Regex("https?://[^\\s]+").find(sharedText)
            val extractedUrl = urlMatch?.value ?: sharedText.trim()

            if (extractedUrl.isBlank()) {
                tvStatus.text = "❌ Пустая ссылка"
                return
            }

            pendingUrl = extractedUrl

            if (checkPermissions()) {
                startTransferProcess(extractedUrl)
            } else {
                requestRequiredPermissions()
            }
        } else {
            tvStatus.text = "❌ Поделитесь треком из YouTube или YouTube Music"
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
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
        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 2002)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val url = pendingUrl
        if (url != null) {
            startTransferProcess(url)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startTransferProcess(rawUrl: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Поиск часов
                withContext(Dispatchers.Main) {
                    progressBar.progress = 5
                    tvTitle.text = "🎵 Обработка трека..."
                    tvStatus.text = "🔍 Поиск часов Huawei в сопряжённых Bluetooth..."
                }

                val watch = findHuaweiWatch()
                    ?: throw Exception("Часы Huawei не найдены в сопряжённых устройствах.\nВключите Bluetooth и сопрягите часы.")

                withContext(Dispatchers.Main) {
                    progressBar.progress = 15
                    tvStatus.text = "✅ Найдено: ${watch.name}\n🔍 Извлечение аудиопотока..."
                }

                // 2. Нормализация URL
                val cleanUrl = rawUrl
                    .replace("music.youtube.com", "www.youtube.com")
                    .replace("m.youtube.com", "www.youtube.com")

                val extractor: StreamExtractor = ServiceList.YouTube.getStreamExtractor(cleanUrl)
                extractor.fetchPage()

                val trackTitle = extractor.name
                val trackArtist = try { extractor.uploaderName } catch (e: Exception) { "YouTube" }

                withContext(Dispatchers.Main) {
                    progressBar.progress = 30
                    tvTitle.text = "🎵 $trackTitle"
                    tvStatus.text = "🎤 $trackArtist\n⬇️ Скачивание звуковой дорожки..."
                }

                // 3. Выбор лучшего аудиопотока
                val audioStream: AudioStream = extractor.audioStreams
                    .filter { it.format?.mimeType?.contains("audio") == true || it.format?.mimeType?.contains("mp4") == true }
                    .maxByOrNull { it.averageBitrate }
                    ?: extractor.audioStreams.firstOrNull()
                    ?: throw Exception("Аудиопоток не найден на YouTube")

                val audioUrl = audioStream.content

                // 4. Скачивание аудиофайла
                val outDir = File(externalCacheDir ?: cacheDir, "shadowmusic").also { it.mkdirs() }
                val safeTitle = trackTitle.replace(Regex("[^\\w\\sА-Яа-яЁё-]"), "").trim().take(70)
                val ext = if (audioStream.format?.mimeType?.contains("webm") == true) "webm" else "m4a"
                val audioFile = File(outDir, "$safeTitle.$ext")

                val dlReq = Request.Builder()
                    .url(audioUrl)
                    .header("User-Agent", "Mozilla/5.0")
                    .header("Referer", "https://www.youtube.com/")
                    .build()

                val dlResp = httpClient.newCall(dlReq).execute()
                val body = dlResp.body ?: throw Exception("Пустой ответ при скачивании аудио")
                val totalLength = body.contentLength()
                var downloadedBytes = 0L

                FileOutputStream(audioFile).use { outStream ->
                    body.byteStream().use { inStream ->
                        val buffer = ByteArray(16384)
                        var bytesRead: Int
                        while (inStream.read(buffer).also { bytesRead = it } != -1) {
                            outStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead
                            if (totalLength > 0) {
                                val percent = (downloadedBytes * 100 / totalLength).toInt()
                                runOnUiThread {
                                    progressBar.progress = 30 + (percent * 0.45).toInt()
                                }
                            }
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    progressBar.progress = 80
                    tvStatus.text = "📤 Отправка файла (${audioFile.length() / 1024} KB) на ${watch.name}..."
                }

                // 5. Отправка через Bluetooth OPP
                withContext(Dispatchers.Main) {
                    sendViaBtOpp(watch, audioFile)
                    progressBar.progress = 100
                    tvStatus.text = "✅ Отправлено!\nПодтвердите приём на экране часов."
                    Toast.makeText(applicationContext, "⌚ Примите трек на часах!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.progress = 0
                    tvStatus.text = "❌ Ошибка: ${e.message}"
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun findHuaweiWatch(): BluetoothDevice? {
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = btManager?.adapter ?: return null
        if (!adapter.isEnabled) return null
        return adapter.bondedDevices?.firstOrNull { dev ->
            val name = dev.name ?: ""
            name.contains("HUAWEI", ignoreCase = true) ||
            name.contains("Watch", ignoreCase = true) ||
            name.contains("HONOR", ignoreCase = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendViaBtOpp(device: BluetoothDevice, file: File) {
        val uri: Uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            file
        )
        val intent = Intent("android.btopp.intent.action.SEND_FILE").apply {
            setClassName("com.android.bluetooth", "com.android.bluetooth.opp.BluetoothOppLauncherActivity")
            putExtra("android.btopp.intent.extra.DEVICE_ADDRESS", device.address)
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "audio/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback to generic bluetooth intent
            val fallback = Intent(Intent.ACTION_SEND).apply {
                setPackage("com.android.bluetooth")
                putExtra(Intent.EXTRA_STREAM, uri)
                type = "audio/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(fallback)
        }
    }
}
