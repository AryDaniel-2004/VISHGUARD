package com.vishguard.ai.services

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.vishguard.ai.R
import okhttp3.*
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

// Data Classes para desempaquetar la respuesta JSON del WebSocket
data class AnalisisIA(
    val nivel_riesgo: String? = null,
    val score: Int? = 0,
    val mensaje_alerta: String? = null,
    val recomendacion: String? = null
)

data class WebSocketResponse(
    val texto_detectado: String? = null,
    val transcripcion_completa: String? = null,
    val analisis: AnalisisIA? = null
)

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var container: LinearLayout
    private lateinit var tvShieldStatus: TextView
    private lateinit var tvScore: TextView
    private lateinit var tvRecommendation: TextView

    // WebSocket / Network
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // Mantener WebSocket vivo sin timeouts
        .build()
    private var webSocket: WebSocket? = null
    private val gson = Gson()

    // Grabación AudioRecord
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    private val sampleRate = 16000 // 16kHz ideal para STT
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val TAG = "VishGuardOverlay"
    // IP de tu servidor backend FastAPI
    private val WS_URL = "ws://10.155.14.216:8000/ws/stream-audio"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 [ANDROID LOG]: OverlayService creado. Iniciando servicio y UI...")
        startForegroundServiceNotification()
        setupOverlayWindow()
        conectarWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "🎙️ [ANDROID LOG]: Iniciando la captura continua de audio...")
        iniciarAudioRecord()
        return START_STICKY
    }

    private fun startForegroundServiceNotification() {
        val channelId = "vishguard_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "VishGuard Protection Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("VishGuard AI Activo")
            .setContentText("Escaneando llamada en tiempo real...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                1,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun setupOverlayWindow() {
        if (overlayView != null) return

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_layout, null)

        container = overlayView!!.findViewById(R.id.overlayContainer)
        tvShieldStatus = overlayView!!.findViewById(R.id.tvShieldStatus)
        tvScore = overlayView!!.findViewById(R.id.tvScore)
        tvRecommendation = overlayView!!.findViewById(R.id.tvRecommendation)

        val layoutParamsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutParamsType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        params.y = 90

        windowManager.addView(overlayView, params)
        Log.d(TAG, "🎨 [ANDROID LOG]: Tarjeta Flotante (Overlay) lista en pantalla.")
    }

    // ⚡ Inicialización y gestión del WebSocket
    private fun conectarWebSocket() {
        val request = Request.Builder().url(WS_URL).build()
        Log.i(TAG, "⚡ [WEBSOCKET]: Conectando a $WS_URL...")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "✅ [WEBSOCKET]: Conexión establecida exitosamente con el backend.")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "📩 [WEBSOCKET RECIBIDO]: $text")
                try {
                    val responseObj = gson.fromJson(text, WebSocketResponse::class.java)
                    responseObj.analisis?.let { analisis ->
                        updateOverlayUI(analisis, responseObj.texto_detectado)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error procesando el JSON del WebSocket: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "❌ [WEBSOCKET ERROR]: Fallo en la conexión: ${t.message}")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "⚠ [WEBSOCKET]: Cerrando conexión ($code): $reason")
            }
        })
    }

    // 🎙️ Captura de Audio continua optimizada para llamadas
    @SuppressLint("MissingPermission")
    private fun iniciarAudioRecord() {
        if (isRecording) return

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        // 🎯 VOICE_COMMUNICATION activa la supresión de eco del sistema para priorizar el audio entrante
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            channelConfig,
            audioFormat,
            minBufferSize
        )

        isRecording = true
        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ByteArray(2048)
            val chunkStream = ByteArrayOutputStream()

            val chunkDurationMs = 3000 // Ráfaga cada 3 segundos
            val bytesPerSecond = sampleRate * 2 // 16-bit PCM = 2 bytes por sample
            val targetChunkSize = bytesPerSecond * (chunkDurationMs / 1000)

            while (isRecording) {
                val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readBytes > 0) {
                    chunkStream.write(buffer, 0, readBytes)

                    // Al acumular ~3 segundos de audio, se despacha por el WebSocket
                    if (chunkStream.size() >= targetChunkSize) {
                        val audioChunk = chunkStream.toByteArray()
                        Log.d(TAG, "📤 [ENVIANDO AUDIO CHUNK]: ${audioChunk.size} bytes enviado por WS.")

                        webSocket?.send(audioChunk.toByteString())
                        chunkStream.reset()
                    }
                }
            }
        }
        recordingThread?.start()
    }

    // 🎨 Actualización en vivo de la tarjeta flotante en pantalla
    private fun updateOverlayUI(analisis: AnalisisIA, textoDetectado: String?) {
        Handler(Looper.getMainLooper()).post {
            val score = analisis.score ?: 0
            val nivelRecibido = analisis.nivel_riesgo?.uppercase() ?: "BAJO"

            Log.i(TAG, "🎨 [ANDROID UI]: Riesgo: $nivelRecibido | Score: $score% | Transcrito: \"$textoDetectado\"")

            tvScore.text = "$score%"
            container.setBackgroundResource(R.drawable.bg_overlay_card)

            val (colorHex, tituloEstado, recomendacionTexto) = when {
                nivelRecibido == "BAJO" && score <= 25 -> Triple(
                    "#1B5E20",
                    "🛡️ Llamada Segura",
                    analisis.recomendacion ?: "Conversación cotidiana sin indicadores de riesgo."
                )
                nivelRecibido == "MEDIO" || score in 26..60 -> Triple(
                    "#E65100",
                    "⚠️ Sospecha Detectada",
                    analisis.mensaje_alerta ?: analisis.recomendacion ?: "Precaución: Se detectan preguntas o patrones inusuales."
                )
                nivelRecibido == "PELIGROSO" || score > 60 -> Triple(
                    "#B71C1C",
                    "🛑 ALERTA DE FRAUDE",
                    analisis.mensaje_alerta ?: "¡Peligro! No proporcione claves, códigos SMS ni datos bancarios."
                )
                else -> Triple(
                    "#1B5E20",
                    "🛡️ VishGuard Activo",
                    "Escaneando llamada en tiempo real..."
                )
            }

            tvShieldStatus.text = tituloEstado
            tvRecommendation.text = recomendacionTexto

            // Tinta dinámica para los bordes/fondo de la tarjeta según el peligro
            container.background?.let { backgroundDrawable ->
                val wrappedDrawable = androidx.core.graphics.drawable.DrawableCompat.wrap(backgroundDrawable).mutate()
                androidx.core.graphics.drawable.DrawableCompat.setTint(wrappedDrawable, Color.parseColor(colorHex))
                container.background = wrappedDrawable
            }
        }
    }

    private fun detenerAudioRecord() {
        isRecording = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            recordingThread?.interrupt()
            recordingThread = null
        } catch (e: Exception) {
            Log.e(TAG, "Error al detener AudioRecord: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "🛑 Deteniendo OverlayService y cerrando socket...")
        detenerAudioRecord()
        webSocket?.close(1000, "Llamada o Servicio finalizado")
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }
}