package com.vishguard.ai.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class VishingResponse(
    val nivel_riesgo: String?,
    val score: Int?,
    val recomendacion: String?
)

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private lateinit var container: LinearLayout
    private lateinit var tvShieldStatus: TextView
    private lateinit var tvScore: TextView
    private lateinit var tvRecommendation: TextView

    private val client = OkHttpClient()
    private val gson = Gson()
    private val TAG = "VishGuardHTTP"

    companion object {
        const val EXTRA_TEXTO = "extra_texto_llamada"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🚀 [ANDROID LOG]: Servicio OverlayService creado correctamente.")
        startForegroundServiceNotification()
        setupOverlayWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val textoRecibido = intent?.getStringExtra(EXTRA_TEXTO)

        if (!textoRecibido.isNullOrBlank()) {
            Log.d(TAG, "📞 [ANDROID LOG]: Frase enviada para análisis: \"$textoRecibido\"")
            enviarTextoParaAnalizar(textoRecibido)
        } else {
            // Al iniciar por primera vez, el estado inicial es SEGURO (0%)
            Log.d(TAG, "🛡️ [ANDROID LOG]: Protección activa. Estado inicial: SEGURO (0%).")
            updateOverlayUI(
                VishingResponse(
                    nivel_riesgo = "BAJO",
                    score = 0,
                    recomendacion = "Escaneando llamada... No se detectan amenazas."
                )
            )
        }
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
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("VishGuard AI Activo")
            .setContentText("Escaneando llamada en tiempo real...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
        params.y = 100

        windowManager.addView(overlayView, params)
        Log.d(TAG, "🎨 [ANDROID LOG]: Tarjeta Flotante (Overlay) inflada y colocada en pantalla.")
    }

    // 🚀 Petición HTTP POST con Logs hacia FastAPI
    fun enviarTextoParaAnalizar(textoLlamada: String) {
        val urlServer = "http://localhost:8000/analizar-llamada"
        Log.i(TAG, "🌐 [ANDROID LOG]: Conectando a $urlServer...")
        Log.i(TAG, "📤 [ENVIANDO TEXTO]: \"$textoLlamada\"")

        // Feedback inmediato en pantalla mientras responde la IA
        Handler(Looper.getMainLooper()).post {
            tvShieldStatus.text = "🔍 Analizando intención..."
            tvRecommendation.text = "Procesando mensaje con el cerebro de IA..."
        }

        val jsonBody = mapOf("texto" to textoLlamada)
        val bodyString = gson.toJson(jsonBody)
        val body = bodyString.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(urlServer)
            .post(body)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "❌ [ANDROID LOG ERROR]: Falló la conexión con el servidor: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseData = response.body?.string()
                if (response.isSuccessful && responseData != null) {
                    Log.d(TAG, "✅ [ANDROID LOG]: ¡Conexión Exitosa con la PC! Respuesta recibida:")
                    Log.d(TAG, "📩 [JSON RECIBIDO]: $responseData")

                    try {
                        val resultado = gson.fromJson(responseData, VishingResponse::class.java)
                        if (resultado != null) {
                            updateOverlayUI(resultado)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ [ANDROID LOG ERROR]: Error al mapear el JSON: ${e.message}")
                    }
                } else {
                    Log.e(TAG, "⚠️ [ANDROID LOG ERROR]: Servidor respondió con código de error: ${response.code}")
                }
            }
        })
    }

    private fun updateOverlayUI(data: VishingResponse) {
        Handler(Looper.getMainLooper()).post {
            val score = data.score ?: 0
            val nivel = data.nivel_riesgo?.uppercase() ?: "PELIGROSO"

            Log.i(TAG, "🎨 [ANDROID LOG]: Actualizando Interfaz Móvil -> Riesgo: $nivel | Score: $score%")

            tvScore.text = "$score%"
            tvRecommendation.text = data.recomendacion ?: "Analizando llamada..."

            when (nivel) {
                "BAJO", "SEGURO" -> {
                    container.setBackgroundColor(Color.parseColor("#2E7D32")) // Verde 🟢
                    tvShieldStatus.text = "🛡️ Llamada Segura"
                }
                "MEDIO", "SOSPECHOSO" -> {
                    container.setBackgroundColor(Color.parseColor("#E65100")) // Naranja 🟠
                    tvShieldStatus.text = "⚠️ Sospecha Detectada"
                }
                "PELIGROSO", "FRAUDE" -> {
                    container.setBackgroundColor(Color.parseColor("#C62828")) // Rojo 🛑
                    tvShieldStatus.text = "🛑 ALERTA DE FRAUDE"
                }
                else -> {
                    container.setBackgroundColor(Color.parseColor("#C62828"))
                    tvShieldStatus.text = "🛑 ALERTA DE FRAUDE"
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        overlayView?.let {
            windowManager.removeView(it)
            overlayView = null
        }
    }
}