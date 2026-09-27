package org.opencell.app.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import org.opencell.app.scan.QrScanner

/**
 * "Scan QR code": asks for the camera, then shows the viewfinder until a valid
 * code is read. If the camera was denied for good (the system won't ask
 * again), it says so and links to the app's settings instead.
 */
@Composable
fun ScanCode(vm: MainViewModel) {
    val context = LocalContext.current
    var cameraBlocked by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.refreshEnvironment()
        if (granted) {
            cameraBlocked = false
            vm.scanning = true
        } else {
            // No rationale after a denial means "don't ask again": a new request would fail silently.
            val activity = context.findActivity()
            cameraBlocked = activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
        }
    }
    if (vm.scanning && vm.environment.cameraPermission) {
        Column {
            QrScanner(
                onText = vm::onScanned,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.height(8.dp))
            Text("Point the camera at the activation QR code.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { vm.scanning = false }) { Text("Stop scanning") }
        }
    } else if (cameraBlocked && !vm.environment.cameraPermission) {
        Text(
            "Camera access is off for OpenCell. Allow it in the app's settings to scan, or paste the code's text below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
        }) { Text("Open Settings") }
    } else {
        Button(onClick = {
            if (vm.environment.cameraPermission) vm.scanning = true else permission.launch(Manifest.permission.CAMERA)
        }) { Text("Scan QR code") }
    }
}
