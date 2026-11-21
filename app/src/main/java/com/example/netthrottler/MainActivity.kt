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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                ThrottlerScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThrottlerScreen() {
    val context = LocalContext.current
    var selectedProfile by remember { mutableStateOf(Presets.list[0]) }
    var isRunning by remember { mutableStateOf(false) }
    var isGlobalMode by remember { mutableStateOf(true) }
    var selectedAppPkg by remember { mutableStateOf<String?>(null) }
    var selectedAppName by remember { mutableStateOf("Global Mode") }
    var showModeMenu by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }

    val vpnLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = Intent(context, ThrottleVpnService::class.java).apply {
                action = ThrottleVpnService.ACTION_START
                putExtra(ThrottleVpnService.EXTRA_PROFILE_ID, selectedProfile.id)
                if (!isGlobalMode) putExtra(ThrottleVpnService.EXTRA_TARGET_PKG, selectedAppPkg)
            }
            context.startForegroundService(intent)
            isRunning = true
        }
    }

    fun toggleVpn() {
        if (isRunning) {
            val intent = Intent(context, ThrottleVpnService::class.java).apply {
                action = ThrottleVpnService.ACTION_STOP
            }
            context.startService(intent)
            isRunning = false
        } else {
            val prepareIntent = VpnService.prepare(context)
            if (prepareIntent != null) {
                vpnLauncher.launch(prepareIntent)
            } else {
                val intent = Intent(context, ThrottleVpnService::class.java).apply {
                    action = ThrottleVpnService.ACTION_START
                    putExtra(ThrottleVpnService.EXTRA_PROFILE_ID, selectedProfile.id)
                    if (!isGlobalMode) putExtra(ThrottleVpnService.EXTRA_TARGET_PKG, selectedAppPkg)
                }
                context.startForegroundService(intent)
                isRunning = true
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Box {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable { showModeMenu = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = Color(0xFF6B85E3),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = if (isGlobalMode) "Global Mode" else selectedAppName,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = showModeMenu,
                            onDismissRequest = { showModeMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Global Mode (Semua Aplikasi)") },
                                onClick = {
                                    isGlobalMode = true
                                    selectedAppPkg = null
                                    showModeMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Pilih Aplikasi Tertentu...") },
                                onClick = {
                                    showModeMenu = false
                                    showAppPicker = true
                                }
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar(
                containerColor = Color.White,
                tonalElevation = 4.dp
            ) {
                IconButton(onClick = { /* TODO: Custom profile dialog */ }) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings")
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { toggleVpn() },
                shape = CircleShape,
                containerColor = if (isRunning) Color(0xFFE53935) else Color(0xFF6B85E3),
                contentColor = Color.White,
                modifier = Modifier.offset(y = 28.dp)
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = "Start/Stop"
                )
            }
        },
        floatingActionButtonPosition = FabPosition.End
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            items(Presets.list) { profile ->
                val isSelected = profile.id == selectedProfile.id
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedProfile = profile
                            if (isRunning) {
                                // Restart VPN dengan profil baru
                                toggleVpn()
                                toggleVpn()
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isSelected) Color(0xFF6B85E3) else Color(0xFF212121)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = profile.subtitle,
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = Color.Gray
                    )
                }
            }
        }
    }

    if (showAppPicker) {
        val pm = context.packageManager
        val apps = remember {
            pm.getInstalledApplications(0)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { it.packageName to pm.getApplicationLabel(it).toString() }
                .sortedBy { it.second }
        }
        AlertDialog(
            onDismissRequest = { showAppPicker = false },
            title = { Text("Pilih Aplikasi Target") },
            text = {
                LazyColumn(modifier = Modifier.height(350.dp)) {
                    items(apps) { (pkg, name) ->
                        Text(
                            text = name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    isGlobalMode = false
                                    selectedAppPkg = pkg
                                    selectedAppName = name
                                    showAppPicker = false
                                }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAppPicker = false }) { Text("Batal") }
            }
        )
    }
}
