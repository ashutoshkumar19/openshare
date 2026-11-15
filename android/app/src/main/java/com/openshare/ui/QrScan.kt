package com.openshare.ui

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun QrCodeImage(data: String, sizePx: Int = 720, modifier: Modifier = Modifier) {
    val bmp = remember(data, sizePx) { generateQrBitmap(data, sizePx) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White)
            .padding(14.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(),
                contentDescription = "OpenShare QR code",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            )
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
            ) {
                Text("Unable to generate QR", style = MaterialTheme.typography.titleMedium, color = SBColors.Danger)
                Text("Use Advanced → payload text as fallback.", style = MaterialTheme.typography.bodySmall, color = SBColors.Muted)
            }
        }
    }
}

private fun generateQrBitmap(text: String, size: Int): Bitmap? = try {
    val bits = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (bits[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    bmp
} catch (_: Exception) { null }

@Composable
fun QrScannerDialog(onDismiss: () -> Unit, onScanned: (String) -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) hasPermission = true else permLauncher.launch(Manifest.permission.CAMERA)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Scan Host QR") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Point the camera at the OpenShare QR shown in the host app (JSON payload). It will connect automatically.", style = MaterialTheme.typography.bodyMedium, color = SBColors.Muted)
                if (!hasPermission) {
                    Text("Camera permission is required to scan QR codes.", color = SBColors.Danger, style = MaterialTheme.typography.bodyMedium)
                } else {
                    var handled by remember { mutableStateOf(false) }
                    Box(Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                        AndroidView(factory = { ctx ->
                            DecoratedBarcodeView(ctx).apply {
                                barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                                decodeContinuous(object : BarcodeCallback {
                                    override fun barcodeResult(result: BarcodeResult?) {
                                        val txt = result?.text ?: return
                                        if (!handled) { handled = true; post { onScanned(txt); onDismiss() } }
                                    }
                                })
                                resume()
                            }
                        }, modifier = Modifier.fillMaxSize(), onRelease = { it.pause() })
                    }
                }
            }
        }
    )
}
