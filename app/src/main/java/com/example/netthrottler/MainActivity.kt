package com.example.netthrottler

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap

private val ClassicBlack = Color(0xFF000000)
private val ClassicSurface = Color(0xFF0D0D0D)
private val ClassicSurfaceActive = Color(0xFF171C26)
private val ClassicBorder = Color(0xFF242424)
private val ClassicAccent = Color(0xFF4C8BF5)
private val ClassicGreen = Color(0xFF2EBD85)
private val ClassicRed = Color(0xFFE5484D)
private val ClassicTextPrimary = Color(0xFFF2F2F2)
private val ClassicTextSecondary = Color(0xFF8C8C8C)

private val ClassicDarkColors = darkColorScheme(
    primary = ClassicAccent,
    background = ClassicBlack,
    surface = ClassicSurface,
    onPrimary = Color.White,
    onBackground = ClassicTextPrimary,
    onSurface = ClassicTextPrimary,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = ClassicDarkColors) {
                ThrottlerScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThrottlerScreen() {
    val context = LocalContext.current

    var customProfiles by remember {
        mutableStateOf(Presets.loadCustomProfiles(context))
    }
    var selectedProfile by remember { mutableStateOf(Presets.list[0]) }
    var speedUnit by remember { mutableStateOf("KB/s") }
    var lastPresetClickMs by remember { mutableStateOf(0L) }
    var editingProfile by remember { mutableStateOf<ThrottleProfile?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var isGlobalMode by remember { mutableStateOf(true) }
    var selectedAppPkg by remember { mutableStateOf<String?>(null) }
    var selectedAppName by remember { mutableStateOf("Global Mode") }
    var isHotspotMode by remember { mutableStateOf(false) }
    var hotspotIp by remember { mutableStateOf("") }

    var showModeMenu by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ -> }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun sendStartService(profile: ThrottleProfile) {
        val intent = Intent(context, ThrottleVpnService::class.java).apply {
            action = ThrottleVpnService.ACTION_START
            putExtra(ThrottleVpnService.EXTRA_PROFILE_NAME, profile.name)
            putExtra(ThrottleVpnService.EXTRA_PROFILE_SUB, profile.subtitle)
            putExtra(ThrottleVpnService.EXTRA_DOWN_BPS, profile.downloadBps)
            putExtra(ThrottleVpnService.EXTRA_UP_BPS, profile.uploadBps)
            putExtra(ThrottleVpnService.EXTRA_LATENCY_MS, profile.latencyMs)
            putExtra(ThrottleVpnService.EXTRA_HOTSPOT_MODE, isHotspotMode)
            putExtra(ThrottleVpnService.EXTRA_SPEED_UNIT, speedUnit)
            if (isHotspotMode) {
                hotspotIp = ThrottleVpnService().getLocalHotspotIp()
            } else if (!isGlobalMode && !selectedAppPkg.isNullOrBlank()) {
                putExtra(ThrottleVpnService.EXTRA_TARGET_PKG, selectedAppPkg)
            }
        }
        context.startForegroundService(intent)
        isRunning = true
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            sendStartService(selectedProfile)
        }
    }

    fun startOrRestartVpn(profile: ThrottleProfile = selectedProfile) {
        selectedProfile = profile
        if (isHotspotMode) {
            sendStartService(profile)
            return
        }
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            vpnLauncher.launch(prepareIntent)
        } else {
            sendStartService(profile)
        }
    }

    fun stopVpn() {
        val intent = Intent(context, ThrottleVpnService::class.java).apply {
            action = ThrottleVpnService.ACTION_STOP
        }
        context.startService(intent)
        isRunning = false
    }

    val allProfiles = remember(customProfiles) {
        Presets.list + customProfiles
    }

    Scaffold(
        containerColor = ClassicBlack,
        topBar = {
            Column(modifier = Modifier.background(ClassicBlack)) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = ClassicBlack,
                        titleContentColor = ClassicTextPrimary,
                    ),
                    title = {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .border(1.dp, ClassicBorder, RoundedCornerShape(6.dp))
                                    .background(ClassicSurface, RoundedCornerShape(6.dp))
                                    .clickable { showModeMenu = true }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = ClassicAccent,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isGlobalMode) "Global Mode" else selectedAppName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ClassicTextPrimary,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = ClassicTextSecondary,
                                )
                            }

                            DropdownMenu(
                                expanded = showModeMenu,
                                onDismissRequest = { showModeMenu = false },
                                modifier = Modifier.background(ClassicSurface),
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Global Mode (Semua App)", color = ClassicTextPrimary) },
                                    onClick = {
                                        isHotspotMode = false
                                        isGlobalMode = true
                                        selectedAppPkg = null
                                        selectedAppName = "Global Mode"
                                        showModeMenu = false
                                        if (isRunning) startOrRestartVpn()
                                    },
                                )
                                HorizontalDivider(color = ClassicBorder)
                                DropdownMenuItem(
                                    text = { Text(if (speedUnit == "KB/s") "Satuan: KB/s ✓" else "Satuan: KB/s", color = ClassicTextPrimary) },
                                    onClick = {
                                        speedUnit = "KB/s"
                                        showModeMenu = false
                                        if (isRunning) startOrRestartVpn()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (speedUnit == "Mbps") "Satuan: Mbps ✓" else "Satuan: Mbps", color = ClassicTextPrimary) },
                                    onClick = {
                                        speedUnit = "Mbps"
                                        showModeMenu = false
                                        if (isRunning) startOrRestartVpn()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (speedUnit == "MBps") "Satuan: MB/s ✓" else "Satuan: MB/s", color = ClassicTextPrimary) },
                                    onClick = {
                                        speedUnit = "MBps"
                                        showModeMenu = false
                                        if (isRunning) startOrRestartVpn()
                                    },
                                )
                                HorizontalDivider(color = ClassicBorder)
                                DropdownMenuItem(
                                    text = { Text("Pilih Aplikasi Target...", color = ClassicTextPrimary) },
                                    onClick = {
                                        isHotspotMode = false
                                        showModeMenu = false
                                        showAppPicker = true
                                    },
                                )
                                HorizontalDivider(color = ClassicBorder)
                                DropdownMenuItem(
                                    text = { Text("Hotspot Proxy (IP:8080)", color = ClassicAccent) },
                                    onClick = {
                                        isHotspotMode = true
                                        isGlobalMode = false
                                        selectedAppPkg = null
                                        selectedAppName = "Hotspot Proxy"
                                        hotspotIp = ThrottleVpnService().getLocalHotspotIp()
                                        showModeMenu = false
                                        if (isRunning) startOrRestartVpn()
                                    },
                                )
                            }
                        }
                    },
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ClassicSurface)
                        .border(width = 1.dp, color = ClassicBorder)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (isRunning) ClassicGreen else ClassicTextSecondary,
                                    RoundedCornerShape(2.dp),
                                ),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRunning) "ACTIVE • ${selectedProfile.name.uppercase()}" else "STOPPED",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isRunning) ClassicGreen else ClassicTextSecondary,
                        )
                    }
                    Text(
                        text = selectedProfile.subtitle,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ClassicTextSecondary,
                    )
                }

                if (isHotspotMode) {
                    val ipDisplay = hotspotIp.ifBlank { ThrottleVpnService().getLocalHotspotIp() }
                    Text(
                        text = "Proxy Klien Wi-Fi: $ipDisplay:${ThrottleVpnService.PROXY_PORT}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ClassicAccent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ClassicSurfaceActive)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ClassicSurface)
                    .border(width = 1.dp, color = ClassicBorder)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        if (isRunning) stopVpn() else startOrRestartVpn()
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) ClassicRed else ClassicAccent,
                        contentColor = Color.White,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isRunning) "STOP THROTTLE" else "START THROTTLE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(ClassicBlack)
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(allProfiles, key = { it.id }) { profile ->
                val isSelected = profile.id == selectedProfile.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (isSelected) ClassicSurfaceActive else ClassicSurface,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) ClassicAccent else ClassicBorder,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .clickable {
                            val now = System.currentTimeMillis()
                            // Tolak tap ganda / ke-tap pas scroll, kalau di bawah 800ms abaikan
                            if (now - lastPresetClickMs < 800) return@clickable
                            // Kalau presetnya sama dan udah jalan, jangan restart lagi
                            if (profile.id == selectedProfile.id && isRunning) return@clickable
                            lastPresetClickMs = now
                            selectedProfile = profile
                            if (isRunning) {
                                startOrRestartVpn(profile)
                            }
                        }
                        .padding(start = 14.dp, end = 6.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) ClassicAccent else ClassicTextSecondary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) Color.White else ClassicTextPrimary,
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = profile.subtitle,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (isSelected) ClassicAccent else ClassicTextSecondary,
                        )
                    }
                    IconButton(
                        onClick = { editingProfile = profile },
                        modifier = Modifier.size(38.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Custom Limit",
                            tint = if (isSelected) ClassicAccent else ClassicTextSecondary,
                        )
                    }
                }
            }
        }
    }

    // Dialog Simpan / Edit Custom Preset
    editingProfile?.let { target ->
        var nameInput by remember(target) {
            mutableStateOf(if (target.isCustom) target.name else "${target.name} Custom")
        }
        var downInput by remember(target) {
            mutableStateOf(if (target.downloadBps > 0) ((target.downloadBps * 8) / 1024).toString() else "0")
        }
        var upInput by remember(target) {
            mutableStateOf(if (target.uploadBps > 0) ((target.uploadBps * 8) / 1024).toString() else "0")
        }
        var latencyInput by remember(target) {
            mutableStateOf(target.latencyMs.toString())
        }

        AlertDialog(
            onDismissRequest = { editingProfile = null },
            containerColor = ClassicSurface,
            titleContentColor = ClassicTextPrimary,
            title = {
                Text(
                    text = if (target.isCustom) "Edit Custom Preset" else "Simpan Custom Preset",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { nameInput = it },
                        label = { Text("Nama Preset") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = downInput,
                        onValueChange = { downInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Download (kbps, 0 = Unlimited)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = upInput,
                        onValueChange = { upInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Upload (kbps, 0 = Unlimited)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = latencyInput,
                        onValueChange = { latencyInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Latency / Delay (ms)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (target.isCustom) {
                        TextButton(
                            onClick = {
                                val updatedList = customProfiles.filterNot { it.id == target.id }
                                customProfiles = updatedList
                                Presets.saveCustomProfiles(context, updatedList)
                                if (selectedProfile.id == target.id) {
                                    selectedProfile = Presets.list[0]
                                    if (isRunning) startOrRestartVpn(Presets.list[0])
                                }
                                editingProfile = null
                            },
                        ) {
                            Text("Hapus", color = ClassicRed)
                        }
                    }
                    Button(
                        onClick = {
                            val d = downInput.toLongOrNull() ?: 0L
                            val u = upInput.toLongOrNull() ?: 0L
                            val l = latencyInput.toLongOrNull() ?: 0L
                            val savedPreset = Presets.createCustom(
                                name = nameInput,
                                downKbps = d,
                                upKbps = u,
                                latencyMs = l,
                                existingId = if (target.isCustom) target.id else null,
                            )
                            val updatedList = if (target.isCustom) {
                                customProfiles.map { if (it.id == target.id) savedPreset else it }
                            } else {
                                customProfiles + savedPreset
                            }
                            customProfiles = updatedList
                            Presets.saveCustomProfiles(context, updatedList)
                            selectedProfile = savedPreset
                            editingProfile = null
                            if (isRunning) {
                                startOrRestartVpn(savedPreset)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ClassicAccent),
                    ) {
                        Text("Simpan")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { editingProfile = null }) {
                    Text("Batal", color = ClassicTextSecondary)
                }
            },
        )
    }

// Dialog App Picker + Pencarian
    if (showAppPicker) {
        val pm = context.packageManager
        val apps = remember {
            pm.getInstalledApplications(0)
                .filter { it.packageName != context.packageName } // Hapus filter sistem agar app bawaan/pre-installed ikut muncul
                .map { appInfo ->
                    Triple(
                        appInfo.packageName,
                        pm.getApplicationLabel(appInfo).toString(),
                        appInfo.loadIcon(pm),
                    )
                }
                .sortedBy { it.second.lowercase() }
        }
        var searchQuery by remember { mutableStateOf("") }
        val filteredApps = remember(searchQuery, apps) {
            if (searchQuery.isBlank()) {
                apps
            } else {
                val q = searchQuery.trim().lowercase()
                apps.filter { (pkg, name, _) ->
                    name.lowercase().contains(q)
                }
            }
        }
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            containerColor = ClassicSurface,
            title = { Text("Pilih Aplikasi Target", color = ClassicTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Ketik nama aplikasi (cth: TikTok Lite)...", fontSize = 13.sp, color = ClassicTextSecondary) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Cari",
                                tint = ClassicAccent,
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Hapus",
                                        tint = ClassicTextSecondary,
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LazyColumn(
                        modifier = Modifier.height(320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filteredApps) { (pkg, name, icon) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, ClassicBorder, RoundedCornerShape(4.dp))
                                    .clickable {
                                        isGlobalMode = false
                                        selectedAppPkg = pkg
                                        selectedAppName = name
                                        showAppPicker = false
                                        if (isRunning) startOrRestartVpn()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val bitmap = icon.toBitmap().asImageBitmap()
                                if (bitmap != null) {
                                    androidx.compose.foundation.Image(
                                        bitmap = bitmap,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Apps,
                                        contentDescription = null,
                                        tint = ClassicTextSecondary,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = name, color = ClassicTextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text(text = pkg, color = ClassicTextSecondary, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppPicker = false }) {
                    Text("Batal", color = ClassicTextSecondary)
                }
            },
        )
    }
}
