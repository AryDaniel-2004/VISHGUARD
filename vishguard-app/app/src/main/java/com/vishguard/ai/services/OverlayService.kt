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
import android.os.IBinder
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

data class VishingResponse(
    val nivel_riesgo: String?,
    val score: Int?,
    val recomendacion: String?
)

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var container: LinearLayout
    private lateinit var tvShieldStatus: TextView
    private lateinit var tvScore: TextView
    private lateinit var tvRecommendation: TextView

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient()
    private val gson = Gson()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        setupOverlayWindow()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connectWebSocket()
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
            .setContentText("Protección de llamadas activada")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    private fun setupOverlayWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        overlayView = LayoutInflater.from(this).inflate(R.layout.overlay_layout, null)

        container = overlayView.findViewById(R.id.overlayContainer)
        tvShieldStatus = overlayView.findViewById(R.id.tvShieldStatus)
        tvScore = overlayView.findViewById(R.id.tvScore)
        tvRecommendation = overlayView.findViewById(R.id.tvRecommendation)

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
    }

    private fun connectWebSocket() {
        webSocket?.close(1000, "Reconectando...")

        // Mantenemos tu IP configurada original para conectar con tu backend
        val request = Request.Builder().url("ws://10.0.2.2:8000/ws/stream").build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("VishGuardWS", "✅ Pixel 8 Conectado al WebSocket!")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                super.onMessage(webSocket, text)
                Log.d("VishGuardWS", "📩 Mensaje recibido: $text")

                try {
                    // Procesamos el JSON recibido usando Gson directamente hacia el data class
                    val responseData = gson.fromJson(text, VishingResponse::class.java)
                    if (responseData != null) {
                        // Actualizamos la interfaz gráfica de forma segura en pantalla
                        updateOverlayUI(responseData)
                    }
                } catch (e: Exception) {
                    Log.e("VishGuardWS", "❌ Error al parsear JSON con Gson: ${e.message}")
                    e.printStackTrace()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("VishGuardWS", "❌ Error en Pixel 8: ${t.message}")
                // Intenta reconectar cada 3 segundos si el servidor se interrumpió
                overlayView.postDelayed({ connectWebSocket() }, 3000)
            }
        })
    }

    private fun updateOverlayUI(data: VishingResponse) {
        // 📌 Forzamos explícitamente que la actualización gráfica corra en el Hilo Principal de Android
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val score = data.score ?: 0
                tvScore.text = "$score%"
                tvRecommendation.text = data.recomendacion ?: "Analizando..."

                val riesgo = data.nivel_riesgo?.uppercase() ?: "BAJO"
                Log.d("VishGuardUI", "🎨 Actualizando UI a nivel de riesgo: $riesgo")

                when (riesgo) {
                    "BAJO", "SEGURO" -> {
                        container.setBackgroundColor(Color.parseColor("#2E7D32")) // Verde 🟢
                        tvShieldStatus.text = "🛡️ Llamada Segura"
                    }
                    "MEDIO", "SOSPECHOSO" -> {
                        container.setBackgroundColor(Color.parseColor("#E65100")) // Naranja 🟠
                        tvShieldStatus.text = "⚠️ Sospecha Detectada"
                    }
                    "PELIGROSO", "FRAUDE", "CRITICO" -> {
                        container.setBackgroundColor(Color.parseColor("#C62828")) // Rojo 🛑
                        tvShieldStatus.text = "🛑 ALERTA DE FRAUDE"
                    }
                    else -> {
                        container.setBackgroundColor(Color.parseColor("#2E7D32"))
                        tvShieldStatus.text = "🛡️ VishGuard Activo"
                    }
                }
            } catch (e: Exception) {
                Log.e("VishGuardUI", "❌ Error actualizando la interfaz: ${e.message}")
            }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        webSocket?.close(1000, "Servicio Detenido")
        if (::overlayView.isInitialized) {
            windowManager.removeView(overlayView)
        }
    }
}