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
import androidx.compose.foundation.BorderStroke
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
    var isRunning by remember { mutableStateOf(false) }
    var isGlobalMode by remember { mutableStateOf(true) }
    var selectedAppPkg by remember { mutableStateOf<String?>(null) }
    var selectedAppName by remember { mutableStateOf("Global Mode") }

    var showModeMenu by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var showCustomDialog by remember { mutableStateOf(false) }

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
                    },
                    actions = {
                        IconButton(onClick = { showCustomDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Custom Limit",
                                tint = ClassicTextPrimary
                            )
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = { showCustomDialog = true },
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, ClassicBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = ClassicBlack,
                        contentColor = ClassicTextPrimary
                    ),
                    modifier = Modifier.height(46.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Custom", fontSize = 14.sp)
                }

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
                        .weight(1f)
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
                            if (profile.id == "custom" && isSelected) {
                                showCustomDialog = true
                            } else {
                                selectedProfile = profile
                                if (isRunning) {
                                    startOrRestartVpn(profile)
                                }
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
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
                    if (profile.id == "custom") {
                        IconButton(
                            onClick = { showCustomDialog = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Custom",
                                tint = ClassicTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = ClassicTextSecondary
                        )
                    }
                }
            }
        }
    }

    // Dialog Custom Limit
    if (showCustomDialog) {
        var downInput by remember {
            mutableStateOf(if (customProfile.downloadBps > 0) ((customProfile.downloadBps * 8) / 1024).toString() else "512")
        }
        var upInput by remember {
            mutableStateOf(if (customProfile.uploadBps > 0) ((customProfile.uploadBps * 8) / 1024).toString() else "256")
        }
        var latencyInput by remember {
            mutableStateOf(customProfile.latencyMs.toString())
        }

        AlertDialog(
            onDismissRequest = { showCustomDialog = false },
            containerColor = ClassicSurface,
            titleContentColor = ClassicTextPrimary,
            title = {
                Text("Custom Limit", fontWeight = FontWeight.Bold, fontSize = 17.sp)
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
                        showCustomDialog = false
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
                TextButton(onClick = { showCustomDialog = false }) {
                    Text("Batal", color = ClassicTextSecondary)
                }
            }
        )
    }

    // Dialog App Picker
    if (showAppPicker) {
        val pm = context.packageManager
        val apps = remember {
            pm.getInstalledApplications(0)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != context.packageName }
                .map { it.packageName to pm.getApplicationLabel(it).toString() }
                .sortedBy { it.second.lowercase() }
        }
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            containerColor = ClassicSurface,
            title = { Text("Pilih Aplikasi Target", color = ClassicTextPrimary) },
            text = {
                LazyColumn(
                    modifier = Modifier.height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(apps) { (pkg, name) ->
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
            },
            confirmButton = {
                TextButton(onClick = { showAppPicker = false }) {
                    Text("Batal", color = ClassicTextSecondary)
                }
            }
        )
    }
}
