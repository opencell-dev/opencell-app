package org.opencell.app.ui

import android.Manifest
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.opencell.app.scan.QrScanner

/** "Scan QR code": asks for the camera once, then shows the viewfinder until a valid code is read. */
@Composable
fun ScanCode(vm: MainViewModel) {
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.refreshEnvironment()
        if (granted) vm.scanning = true
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
    } else {
        Button(onClick = {
            if (vm.environment.cameraPermission) vm.scanning = true else permission.launch(Manifest.permission.CAMERA)
        }) { Text("Scan QR code") }
    }
}
