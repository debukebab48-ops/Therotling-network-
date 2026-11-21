package com.example.netthrottler

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    onSurface = ClassicTextPrimary
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

    var customProfile by remember {
        mutableStateOf(Presets.createCustom(downKbps = 512, upKbps = 256, latencyMs = 80))
    }
    var selectedProfile by remember { mutableStateOf(Presets.list[0]) }
    var editingProfile by remember { mutableStateOf<ThrottleProfile?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var isGlobalMode by remember { mutableStateOf(true) }
    var selectedAppPkg by remember { mutableStateOf<String?>(null) }
    var selectedAppName by remember { mutableStateOf("Global Mode") }

    var showModeMenu by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }

    fun sendStartService(profile: ThrottleProfile) {
        val intent = Intent(context, ThrottleVpnService::class.java).apply {
            action = ThrottleVpnService.ACTION_START
            putExtra(ThrottleVpnService.EXTRA_PROFILE_NAME, profile.name)
            putExtra(ThrottleVpnService.EXTRA_PROFILE_SUB, profile.subtitle)
            putExtra(ThrottleVpnService.EXTRA_DOWN_BPS, profile.downloadBps)
            putExtra(ThrottleVpnService.EXTRA_UP_BPS, profile.uploadBps)
            putExtra(ThrottleVpnService.EXTRA_LATENCY_MS, profile.latencyMs)
            if (!isGlobalMode && !selectedAppPkg.isNullOrBlank()) {
                putExtra(ThrottleVpnService.EXTRA_TARGET_PKG, selectedAppPkg)
            }
        }
        context.startForegroundService(intent)
        isRunning = true
    }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            sendStartService(selectedProfile)
        }
    }

    fun startOrRestartVpn(profile: ThrottleProfile = selectedProfile) {
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

    val allProfiles = remember(customProfile) {
        Presets.list + customProfile
    }

    Scaffold(
        containerColor = ClassicBlack,
        topBar = {
            Column(modifier = Modifier.background(ClassicBlack)) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = ClassicBlack,
                        titleContentColor = ClassicTextPrimary
                    ),
                    title = {
                        Box {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .border(1.dp, ClassicBorder, RoundedCornerShape(6.dp))
                                    .background(ClassicSurface, RoundedCornerShape(6.dp))
                                    .clickable { showModeMenu = true }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    tint = ClassicAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isGlobalMode) "Global Mode" else selectedAppName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = ClassicTextPrimary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = null,
                                    tint = ClassicTextSecondary
                                )
                            }

                            DropdownMenu(
                                expanded = showModeMenu,
                                onDismissRequest = { showModeMenu = false },
                                modifier = Modifier
                                    .background(ClassicSurface)
                                    .border(1.dp, ClassicBorder)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Global Mode (Semua App)", color = ClassicTextPrimary) },
                                    onClick = {
                                        isGlobalMode = true
                                        selectedAppPkg = null
                                        showModeMenu = false
                                        if (isRunning) startOrRestartVpn()
                                    }
                                )
                                HorizontalDivider(color = ClassicBorder)
                                DropdownMenuItem(
                                    text = { Text("Pilih Aplikasi Target...", color = ClassicTextPrimary) },
                                    onClick = {
                                        showModeMenu = false
                                        showAppPicker = true
                                    }
                                )
                            }
                        }
                    }
                )

                // Status Bar Classic
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ClassicSurface)
                        .border(width = 1.dp, color = ClassicBorder)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(
                                    if (isRunning) ClassicGreen else ClassicTextSecondary,
                                    RoundedCornerShape(2.dp)
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRunning) "ACTIVE • ${selectedProfile.name.uppercase()}" else "STOPPED",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = if (isRunning) ClassicGreen else ClassicTextSecondary
                        )
                    }
                    Text(
                        text = selectedProfile.subtitle,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = ClassicTextSecondary
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (isRunning) stopVpn() else startOrRestartVpn()
                    },
                    shape = RoundedCornerShape(6.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRunning) ClassicRed else ClassicAccent,
                        contentColor = Color.White
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isRunning) "STOP THROTTLE" else "START THROTTLE",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(ClassicBlack)
                .padding(padding),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(allProfiles) { profile ->
                val isSelected = profile.id == selectedProfile.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = if (isSelected) ClassicSurfaceActive else ClassicSurface,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) ClassicAccent else ClassicBorder,
                            shape = RoundedCornerShape(6.dp)
                        )
                        .clickable {
                            selectedProfile = profile
                            if (isRunning) {
                                startOrRestartVpn(profile)
                            }
                        }
                        .padding(start = 14.dp, end = 6.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (isSelected) Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) ClassicAccent else ClassicTextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isSelected) Color.White else ClassicTextPrimary
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = profile.subtitle,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (isSelected) ClassicAccent else ClassicTextSecondary
                        )
                    }
                    // Tombol Custom Limit dipindah ke posisi tanda panah kanan ini
                    IconButton(
                        onClick = { editingProfile = profile },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Custom Limit",
                            tint = if (isSelected) ClassicAccent else ClassicTextSecondary
                        )
                    }
                }
            }
        }
    }

    // Dialog Custom Limit (dibuka dari tombol '>' di kartu)
    editingProfile?.let { target ->
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
                Text("Custom Limit (${target.name})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Isi 0 jika tidak ingin dilimit (Unlimited).",
                        fontSize = 12.sp,
                        color = ClassicTextSecondary
                    )
                    OutlinedTextField(
                        value = downInput,
                        onValueChange = { downInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Download (kbps)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = upInput,
                        onValueChange = { upInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Upload (kbps)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = latencyInput,
                        onValueChange = { latencyInput = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Latency / Delay (ms)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val d = downInput.toLongOrNull() ?: 0L
                        val u = upInput.toLongOrNull() ?: 0L
                        val l = latencyInput.toLongOrNull() ?: 0L
                        val updated = Presets.createCustom(d, u, l)
                        customProfile = updated
                        selectedProfile = updated
                        editingProfile = null
                        if (isRunning) {
                            startOrRestartVpn(updated)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClassicAccent)
                ) {
                    Text("Terapkan")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingProfile = null }) {
                    Text("Batal", color = ClassicTextSecondary)
                }
            }
        )
    }

    // Dialog App Picker + Tombol/Bar Pencarian
    if (showAppPicker) {
        val pm = context.packageManager
        val apps = remember {
            pm.getInstalledApplications(0)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != context.packageName }
                .map { it.packageName to pm.getApplicationLabel(it).toString() }
                .sortedBy { it.second.lowercase() }
        }
        var searchQuery by remember { mutableStateOf("") }
        val filteredApps = remember(searchQuery, apps) {
            if (searchQuery.isBlank()) {
                apps
            } else {
                val q = searchQuery.trim().lowercase()
                apps.filter { (pkg, name) ->
                    name.lowercase().contains(q) || pkg.lowercase().contains(q)
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
                        placeholder = { Text("Cari nama aplikasi...", fontSize = 13.sp, color = ClassicTextSecondary) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Cari",
                                tint = ClassicAccent
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Hapus",
                                        tint = ClassicTextSecondary
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    LazyColumn(
                        modifier = Modifier.height(320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredApps) { (pkg, name) ->
                            Column(
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
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Text(text = name, color = ClassicTextPrimary, fontSize = 14.sp)
                                Text(text = pkg, color = ClassicTextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppPicker = false }) {
                    Text("Batal", color = ClassicTextSecondary)
                }
            }
        )
    }
}
