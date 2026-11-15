package com.openshare.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openshare.R
import com.openshare.session.IncomingOffer

@Composable
fun OpenShareApp(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var pendingAcceptAfterPick by remember { mutableStateOf(false) }
    var qrInput by remember { mutableStateOf("") }
    var showScanner by remember { mutableStateOf(false) }
    var pendingStart by remember { mutableStateOf(false) }

    val requiredStartPerms = remember(vm.mode, vm.role) { requiredPermissionsForStart(vm.mode, vm.role) }

    fun hasAll(perms: List<String>) = perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    val startPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        val missing = requiredStartPerms.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty() && pendingStart) {
            pendingStart = false
            vm.start(contextPreflightMessage(context, vm.mode, vm.role))
        } else if (pendingStart) {
            pendingStart = false
            vm.status = "Permissions required: ${missing.joinToString { prettyPerm(it) }}. Allow them in Settings and tap Start again."
            vm.state = "failed"
            vm.running = false
        }
    }

    fun requestStartOrRun() {
        val missing = requiredStartPerms.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            vm.start(contextPreflightMessage(context, vm.mode, vm.role))
        } else {
            pendingStart = true
            vm.status = "Requesting permissions needed for this mode…"
            startPermLauncher.launch(missing.toTypedArray())
        }
    }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        if (uris.isNotEmpty()) vm.setSelected(uris)
    }
    val pickSendFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (vm.role == Role.SEND) vm.setSelected(listOf(it))
        }
    }
    val pickReceiveFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val wantAccept = pendingAcceptAfterPick
        pendingAcceptAfterPick = false
        if (uri != null && wantAccept && vm.incomingOffer != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            vm.acceptIncomingToChosenFolder(uri)
        }
    }

    if (showScanner) {
        QrScannerDialog(onDismiss = { showScanner = false }, onScanned = { scanned ->
            qrInput = scanned
            vm.connectFromHotspotQr(scanned)
            showScanner = false
        })
    }

    OpenShareTheme {
        Scaffold(containerColor = SBColors.Bg) { pad ->
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                HeroHeader(vm)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    QuickStartCard(
                        vm = vm,
                        hasRequiredPerms = hasAll(requiredStartPerms),
                        onStart = { if (vm.running) vm.stop() else requestStartOrRun() },
                        onOpenSettings = { openAppSettings(context) }
                    )
                    StatusCard(vm)

                    if (vm.role == Role.SEND) {
                        SendCard(vm,
                            onPickFiles = { pickFiles.launch(arrayOf("*/*")) },
                            onPickFolder = { pickSendFolder.launch(null) }
                        )
                    }

                    vm.incomingOffer?.let { offer ->
                        IncomingCard(offer,
                            onAccept = { pendingAcceptAfterPick = true; pickReceiveFolder.launch(null) },
                            onDecline = { vm.declineIncoming() }
                        )
                    }

                    if (vm.mode == Mode.SAME_WIFI) {
                        if (vm.role == Role.SEND) PeersCard(vm)
                        else SameWifiReceiveHint(vm)
                    }

                    if (vm.mode == Mode.HOTSPOT) {
                        HotspotCard(vm, qrInput, { qrInput = it }, onScan = { showScanner = true })
                    }

                    if (vm.progressText.isNotBlank() || vm.progressTotal > 0 || vm.state == "transferring" || vm.state == "verifying") {
                        ProgressCard(vm)
                    }

                    Text(
                        "Offline Wi‑Fi sharing • Receiver must accept • One-to-one • Plain TCP (no TLS)",
                        style = MaterialTheme.typography.bodySmall,
                        color = SBColors.Muted,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

private fun requiredPermissionsForStart(mode: Mode, role: Role): List<String> {
    val perms = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= 33) {
        perms += Manifest.permission.NEARBY_WIFI_DEVICES
        perms += Manifest.permission.POST_NOTIFICATIONS
    } else {
        perms += Manifest.permission.ACCESS_FINE_LOCATION
    }
    return perms.distinct()
}

private fun prettyPerm(p: String): String = when (p) {
    Manifest.permission.NEARBY_WIFI_DEVICES -> "Nearby devices"
    Manifest.permission.ACCESS_FINE_LOCATION -> "Location"
    Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
    else -> p.substringAfterLast('.')
}

private fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}

private fun contextPreflightMessage(context: Context, mode: Mode, role: Role): String? {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val wifiOn = wifi?.isWifiEnabled == true
    val loc = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    val locOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) loc?.isLocationEnabled == true else {
        @Suppress("DEPRECATION")
        (loc?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true || loc?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true)
    }
    if (mode == Mode.HOTSPOT && role == Role.RECEIVE) {
        if (!wifiOn) return "Turn Wi‑Fi ON before starting hotspot (it can stay disconnected), then tap Start again."
        if (!locOn) return "Turn Location services ON to start Local Only Hotspot on this device, then tap Start again."
    }
    return null
}

@Composable
private fun HeroHeader(vm: MainViewModel) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(SBColors.Primary, SBColors.PrimaryDark, SBColors.Bg)
                )
            )
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Image(
                    painter = painterResource(id = R.drawable.openshare_icon),
                    contentDescription = "OpenShare",
                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(14.dp))
                )
                Column(Modifier.weight(1f)) {
                    Text("OpenShare", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Fast offline file & folder sharing", color = Color.White.copy(alpha = 0.88f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                StatePill(vm.state)
                Text(
                    "${if (vm.mode == Mode.HOTSPOT) "Hotspot" else "Same Wi‑Fi"} • ${if (vm.role == Role.SEND) "Sender" else "Receiver"}",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun QuickStartCard(vm: MainViewModel, hasRequiredPerms: Boolean, onStart: () -> Unit, onOpenSettings: () -> Unit) {
    SectionCard("Setup", "Pick mode and role, then start") {
        SegmentedRow("Connection", listOf(Mode.SAME_WIFI to "Same Wi‑Fi", Mode.HOTSPOT to "Hotspot & QR"), vm.mode) { vm.mode = it }
        Spacer(Modifier.height(10.dp))
        SegmentedRow("I want to", listOf(Role.SEND to "Send files", Role.RECEIVE to "Receive files"), vm.role) { vm.role = it }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = onStart,
                colors = ButtonDefaults.buttonColors(containerColor = if (vm.running) SBColors.Danger else SBColors.Primary),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(if (vm.running) Icons.Default.StopCircle else Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (vm.running) "Stop" else "Start Session")
            }
            if (vm.state == "transferring" || vm.state == "awaiting_accept" || vm.state == "awaitingAccept" || vm.state == "verifying") {
                OutlinedButton(onClick = { vm.stop() }, shape = RoundedCornerShape(14.dp)) { Text("Cancel") }
            }
        }
        if (vm.mode == Mode.HOTSPOT) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (vm.role == Role.RECEIVE) "Host mode: creates a Local Only Hotspot and shows the OpenShare QR for the other device. Requires Wi‑Fi ON, Location services ON, and Nearby devices/Location permission."
                else "Join mode: connect this phone to the host’s hotspot Wi‑Fi in system settings, pick files, then scan the host’s OpenShare QR.",
                style = MaterialTheme.typography.bodySmall, color = SBColors.Muted
            )
        }
        if (!hasRequiredPerms && !vm.running) {
            Spacer(Modifier.height(8.dp))
            HintBox("Tap Start to grant required permissions (Nearby devices/Location, Notifications) when prompted.", Icons.Default.Security)
            TextButton(onClick = onOpenSettings) { Text("Open app settings") }
        }
    }
}

@Composable
private fun StatusCard(vm: MainViewModel) {
    SectionCard("Status", null) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(stateColor(vm.state).copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(statusIcon(vm.state), contentDescription = null, tint = stateColor(vm.state), modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(vm.status, style = MaterialTheme.typography.bodyLarge, color = SBColors.Text)
                Spacer(Modifier.height(4.dp))
                Text("Local network only • Receiver confirms before data transfers", style = MaterialTheme.typography.bodySmall, color = SBColors.Muted)
            }
        }
    }
}

@Composable
private fun SendCard(vm: MainViewModel, onPickFiles: () -> Unit, onPickFolder: () -> Unit) {
    SectionCard("Files to Send", "Choose what you want to share") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            FilledTonalButton(onClick = onPickFiles, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Description, null); Spacer(Modifier.width(6.dp)); Text("Pick Files")
            }
            FilledTonalButton(onClick = onPickFolder, shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Folder, null); Spacer(Modifier.width(6.dp)); Text("Pick Folder")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (vm.selectedUris.isEmpty()) {
            HintBox("No items selected. Pick files or a folder before sending.", Icons.Outlined.InsertDriveFile)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.CheckCircle, null, tint = SBColors.Success, modifier = Modifier.size(18.dp))
                Text("${vm.selectedUris.size} item(s) ready to send", color = SBColors.Text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun IncomingCard(offer: IncomingOffer, onAccept: () -> Unit, onDecline: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFECFDF5)), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, SBColors.Success.copy(alpha = 0.35f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(SBColors.Success.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = SBColors.Success)
                }
                Column(Modifier.weight(1f)) {
                    Text("Incoming Transfer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("From ${offer.from}", style = MaterialTheme.typography.bodyMedium, color = SBColors.Text)
                    Text("${offer.offer.fileCount} files • ${formatBytes(offer.offer.totalBytes)}", style = MaterialTheme.typography.bodySmall, color = SBColors.Muted)
                }
            }
            Button(onClick = onAccept, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = SBColors.Success), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp)); Text("Accept & Choose Save Folder")
            }
            OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Decline") }
            Text("Nothing is saved until you choose a destination folder.", style = MaterialTheme.typography.bodySmall, color = SBColors.Muted)
        }
    }
}

@Composable
private fun PeersCard(vm: MainViewModel) {
    SectionCard("Nearby Devices", "Receivers found on this Wi‑Fi") {
        if (vm.peers.isEmpty()) {
            HintBox("Searching for receivers… Make sure the other device tapped Start as Receiver on the same Wi‑Fi (AP isolation can block discovery).", Icons.Default.WifiTethering)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                vm.peers.forEach { p ->
                    Card(colors = CardDefaults.cardColors(containerColor = SBColors.SurfaceAlt), shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(SBColors.Primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Devices, null, tint = SBColors.Primary)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("${p.host}:${p.port}", style = MaterialTheme.typography.bodySmall, color = SBColors.Muted)
                            }
                            Button(onClick = { vm.connectToPeer(p) }, enabled = vm.selectedUris.isNotEmpty(), shape = RoundedCornerShape(10.dp)) { Text("Connect") }
                        }
                    }
                }
                if (vm.selectedUris.isEmpty()) HintBox("Select files or a folder before connecting.", Icons.Default.Info)
            }
        }
    }
}

@Composable
private fun SameWifiReceiveHint(vm: MainViewModel) {
    SectionCard("Ready to Receive", "Stay on this screen") {
        HintBox("Tap Start, then keep OpenShare open. Senders on the same Wi‑Fi will discover this device and send an offer for you to accept.", Icons.Default.Wifi)
        if (vm.running) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SBColors.Primary)
                Text("Advertising on local network…", style = MaterialTheme.typography.bodyMedium, color = SBColors.Primary, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun HotspotCard(vm: MainViewModel, qrInput: String, onQrInput: (String) -> Unit, onScan: () -> Unit) {
    val context = LocalContext.current
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    var showAdvanced by remember { mutableStateOf(false) }
    SectionCard("Hotspot & QR", "Connect with one scan") {
        if (vm.role == Role.RECEIVE) {
            if (!vm.running) {
                HintBox("Tap Start Session to create the hotspot and show connection details for the Mac.", Icons.Default.QrCode2)
            } else if (vm.hotspotQrJson.isBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = SBColors.Primary)
                    Text("Starting hotspot and generating QR…", style = MaterialTheme.typography.bodyMedium, color = SBColors.Primary)
                }
                Text("If this takes long, hotspot may be unavailable — try Same Wi‑Fi mode or check permissions.", style = MaterialTheme.typography.bodySmall, color = SBColors.Muted)
            } else {
                Card(colors = CardDefaults.cardColors(containerColor = SBColors.SurfaceAlt), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("1. On Mac, join this hotspot Wi‑Fi", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (vm.hotspotSsid.isNotBlank()) {
                            SelectionContainer {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Wi‑Fi name (SSID): ${vm.hotspotSsid}", style = MaterialTheme.typography.bodyMedium, color = SBColors.Text, fontWeight = FontWeight.Medium)
                                    Text("Password: ${vm.hotspotPassword.ifBlank { "(not available)" }}", style = MaterialTheme.typography.bodyMedium, color = SBColors.Text, fontWeight = FontWeight.Medium)
                                }
                            }
                            if (vm.hotspotPassword.isNotBlank()) {
                                OutlinedButton(onClick = {
                                    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("OpenShare hotspot password", vm.hotspotPassword))
                                }, shape = RoundedCornerShape(10.dp)) { Icon(Icons.Default.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copy password") }
                            }
                        }
                        Text("2. In OpenShare on Mac (Hotspot Send), scan this QR to connect the transfer", style = MaterialTheme.typography.bodySmall, color = SBColors.Muted)
                        QrCodeImage(vm.hotspotQrJson, modifier = Modifier.fillMaxWidth(0.72f))
                        Text("Scanning identifies this device/session for OpenShare (host, port, token). macOS still requires you to join the hotspot Wi‑Fi in System Settings.", style = MaterialTheme.typography.bodySmall, color = SBColors.Muted)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = SBColors.Success)
                            Text("Waiting for Mac to join Wi‑Fi and connect…", style = MaterialTheme.typography.bodySmall, color = SBColors.Success, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        } else {
            HintBox("1) Join the host device’s hotspot Wi‑Fi in macOS System Settings (use SSID/password shown on host). 2) Select files above. 3) Scan the host’s OpenShare QR to connect — scanning tells OpenShare where to send (host/port/token).", Icons.Default.Info)
            if (vm.selectedUris.isEmpty()) {
                HintBox("Select files or a folder first, then scan the host QR.", Icons.Default.Warning)
            }
            Button(onClick = onScan, enabled = vm.selectedUris.isNotEmpty(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Default.QrCodeScanner, null); Spacer(Modifier.width(8.dp)); Text("Scan Host QR (Camera)")
            }
        }

        TextButton(onClick = { showAdvanced = !showAdvanced }) { Text(if (showAdvanced) "Hide advanced" else "Advanced: paste payload") }
        if (showAdvanced) {
            OutlinedTextField(value = qrInput, onValueChange = onQrInput, label = { Text("Payload (scanned or pasted)") }, modifier = Modifier.fillMaxWidth(), minLines = 2, shape = RoundedCornerShape(12.dp))
            if (vm.role != Role.RECEIVE) {
                Button(onClick = { vm.connectFromHotspotQr(qrInput) }, enabled = qrInput.isNotBlank() && !(vm.role == Role.SEND && vm.selectedUris.isEmpty()), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text("Connect from payload") }
            }
        }
    }
}

@Composable
private fun ProgressCard(vm: MainViewModel) {
    val total = vm.progressTotal.coerceAtLeast(1)
    val frac = (vm.progressBytes.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    SectionCard("Transfer Progress", null) {
        if (vm.progressTotal > 0) {
            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(8.dp)), color = SBColors.Primary, trackColor = SBColors.Border)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${formatBytes(vm.progressBytes)} / ${formatBytes(vm.progressTotal)}", style = MaterialTheme.typography.bodyMedium, color = SBColors.Muted)
                Text("${(frac * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = SBColors.Primary)
            }
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(8.dp)))
        }
        if (vm.progressText.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(vm.progressText, style = MaterialTheme.typography.bodyMedium, color = SBColors.Text)
        }
        if (vm.transferStartMs != null || vm.totalDurationMs != null) {
            Spacer(Modifier.height(6.dp))
            val timeText = if (vm.state == "complete" && vm.totalDurationMs != null) "Total time: ${vm.formatDuration(vm.totalDurationMs!!)}"
                           else "Elapsed: ${vm.formatDuration(vm.elapsedMs)}"
            Text(timeText, style = MaterialTheme.typography.bodySmall, color = SBColors.Muted, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SectionCard(title: String, subtitle: String?, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SBColors.Card), shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SBColors.Muted)
            if (subtitle != null) Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

@Composable
private fun <T> SegmentedRow(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = SBColors.Muted)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) }, shape = RoundedCornerShape(10.dp))
            }
        }
    }
}

@Composable
private fun StatePill(state: String) {
    val c = stateColor(state)
    val label = state.replace('_', ' ').replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    Surface(color = Color.White.copy(alpha = 0.2f), shape = RoundedCornerShape(999.dp)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (state == "idle") Color.White.copy(alpha = 0.9f) else c))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun HintBox(text: String, icon: ImageVector) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SBColors.SurfaceAlt).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = SBColors.Primary, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = SBColors.Muted)
    }
}

private fun statusIcon(state: String): ImageVector = when (state) {
    "idle" -> Icons.Default.PauseCircle
    "discovering" -> Icons.Default.WifiTethering
    "pairing" -> Icons.Default.Link
    "awaiting_accept", "awaitingAccept" -> Icons.Default.HourglassTop
    "transferring" -> Icons.Default.SyncAlt
    "verifying" -> Icons.Default.Verified
    "complete" -> Icons.Default.CheckCircle
    "failed" -> Icons.Default.Error
    "cancelled" -> Icons.Default.Cancel
    else -> Icons.Default.Info
}
