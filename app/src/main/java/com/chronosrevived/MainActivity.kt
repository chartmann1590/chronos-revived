package com.chronosrevived

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SysSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    // Firmware comes from the user's copy of the original Chronos app; we never ship it.
    private val pickFirmware = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        if (Firmware.importFrom(this, uri)) Firmware.install(this)
        else android.widget.Toast.makeText(this, "That file doesn't contain Chronos firmware. Pick the original Chronos APK.", android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Chronos.init(this)
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        askPermissions()
        setContent {
            ChronosApp(
                onScan = { askPermissions(); Chronos.scan() },
                onNotificationAccess = { startActivity(Intent(SysSettings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onInstallFirmware = {
                    if (Firmware.available(this)) Firmware.install(this) else pickFirmware.launch(arrayOf("*/*"))
                },
            )
        }
    }

    override fun onResume() { super.onResume(); Chronos.connect() }

    private fun askPermissions() {
        val perms = if (Build.VERSION.SDK_INT >= 31)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 1)
    }
}
