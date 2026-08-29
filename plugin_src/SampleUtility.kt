package com.omni.plugin

import android.content.Context
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.hub.api.HostBridge
import com.omni.hub.api.PluginEntry

/**
 * Sample dynamic plugin compiled by forge.sh into a standalone bundle.zip
 */
class SampleUtility : PluginEntry() {

    override fun onCreateView(context: Context, bridge: HostBridge, baseDir: String): View {
        return ComposeView(context).apply {
            setContent {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Color(0xFF0F172A),
                        surface = Color(0xFF1E293B),
                        primary = Color(0xFF38BDF8)
                    )
                ) {
                    UtilityScreen(bridge)
                }
            }
        }
    }

    @Composable
    fun UtilityScreen(bridge: HostBridge) {
        var batteryLevel by remember { mutableIntStateOf(bridge.getBatteryLevel()) }
        var isCharging by remember { mutableStateOf(bridge.isCharging()) }
        var sensorData by remember { mutableStateOf("Tap below to sample sensors") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A))
                .statusBarsPadding()
                .padding(24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Dynamic Utility", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("Compiled via forge.sh OTA", fontSize = 13.sp, color = Color(0xFF94A3B8))
                }
                Button(
                    onClick = { bridge.close() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Exit")
                }
            }

            Spacer(Modifier.height(24.dp))

            // Diagnostic Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Host Telemetry", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                    Spacer(Modifier.height(8.dp))
                    Text("Battery: $batteryLevel% (${if (isCharging) "Charging" else "On Battery"})", color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text("Network: ${if (bridge.isNetworkAvailable()) "Active" else "Offline"}", color = Color.White)
                }
            }

            Spacer(Modifier.height(16.dp))

            // Sensors Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Hardware Sensors", fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                    Spacer(Modifier.height(8.dp))
                    Text(sensorData, color = Color(0xFFE2E8F0), fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(24.dp))

            // Action Buttons
            Button(
                onClick = {
                    bridge.vibrate(100L)
                    sensorData = bridge.sampleSensors()
                    batteryLevel = bridge.getBatteryLevel()
                    isCharging = bridge.isCharging()
                    bridge.showToast("Telemetry Refreshed!")
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Refresh Host Telemetry")
            }

            Spacer(Modifier.height(12.dp))

            Button(
                onClick = {
                    val info = bridge.getSystemInfo()
                    bridge.copyToClipboard(info)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF334155)),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Copy System Specs (JSON)")
            }
        }
    }
}