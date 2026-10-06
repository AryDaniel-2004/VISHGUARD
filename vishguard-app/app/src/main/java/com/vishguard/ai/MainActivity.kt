package com.vishguard.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.vishguard.ai.services.OverlayService

class MainActivity : AppCompatActivity() {

    private var isServiceRunning = false
    private lateinit var btnToggleService: Button
    private lateinit var tvStatusInfo: TextView

    companion object {
        private const val OVERLAY_PERMISSION_REQ_CODE = 1234
        private const val AUDIO_PERMISSION_REQ_CODE = 5678
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnToggleService = findViewById(R.id.btnToggleService)
        tvStatusInfo = findViewById(R.id.tvStatusInfo)

        btnToggleService.setOnClickListener {
            if (isServiceRunning) {
                detenerServicio()
            } else {
                verificarPermisosYIniciar()
            }
        }
    }

    private fun verificarPermisosYIniciar() {
        // 1. Verificar permiso de Overlay (SYSTEM_ALERT_WINDOW)
        if (!checkOverlayPermission()) {
            requestOverlayPermission()
            return
        }

        // 2. Verificar permiso de Micrófono (RECORD_AUDIO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                AUDIO_PERMISSION_REQ_CODE
            )
            return
        }

        // Si ambos permisos están concedidos, iniciamos el servicio
        iniciarServicio()
    }

    private fun iniciarServicio() {
        val intent = Intent(this, OverlayService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        isServiceRunning = true
        btnToggleService.text = "DESACTIVAR PROTECCIÓN"
        btnToggleService.setBackgroundColor(android.graphics.Color.parseColor("#C62828"))
        tvStatusInfo.text = "Estado: Escaneando llamada en tiempo real..."
        tvStatusInfo.setTextColor(android.graphics.Color.parseColor("#4CAF50"))
    }

    private fun detenerServicio() {
        val intent = Intent(this, OverlayService::class.java)
        stopService(intent)

        isServiceRunning = false
        btnToggleService.text = "ACTIVAR PROTECCIÓN"
        btnToggleService.setBackgroundColor(android.graphics.Color.parseColor("#2E7D32"))
        tvStatusInfo.text = "Estado: Desconectado"
        tvStatusInfo.setTextColor(android.graphics.Color.parseColor("#757575"))
    }

    private fun checkOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Toast.makeText(
                this,
                "Por favor, concede el permiso para mostrar la alerta sobre otras aplicaciones.",
                Toast.LENGTH_LONG
            ).show()

            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_REQ_CODE)
        }
    }

    // Callback para cuando se otorga/deniega el permiso de micrófono
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_REQ_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                iniciarServicio()
            } else {
                Toast.makeText(
                    this,
                    "Se requiere permiso de micrófono para detectar amenazas en tiempo real.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_PERMISSION_REQ_CODE) {
            if (checkOverlayPermission()) {
                verificarPermisosYIniciar()
            } else {
                Toast.makeText(this, "Permiso de ventana flotante denegado.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}