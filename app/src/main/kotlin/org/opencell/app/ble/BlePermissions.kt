package org.opencell.app.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Runtime permissions the app asks for. */
object BlePermissions {
    /** Nearby devices: scan (declared `neverForLocation`) and connect. */
    val BLUETOOTH = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    /** Everything requested at once: Bluetooth plus notifications for the link's foreground service. */
    val ALL: Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        BLUETOOTH + Manifest.permission.POST_NOTIFICATIONS
    } else {
        BLUETOOTH
    }

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun hasBluetooth(context: Context): Boolean = BLUETOOTH.all { has(context, it) }

    fun hasConnect(context: Context): Boolean = has(context, Manifest.permission.BLUETOOTH_CONNECT)

    fun hasScan(context: Context): Boolean = has(context, Manifest.permission.BLUETOOTH_SCAN)
}
