package io.github.kennethcheong.clauderemote.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

/** One screen: the relay switch, the computers it watches, and the two settings it needs. */
class MainActivity : ComponentActivity() {
    /** Bumped when the user comes back from a settings screen, so the checklist re-reads. */
    private val resumed = mutableIntStateOf(0)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { resumed.intValue++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (WatcherService.isOn(this)) WatcherService.set(this, true)
        setContent {
            val dark = isSystemInDarkTheme()
            val accent = Color(0xFFD97757)
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = accent) else lightColorScheme(primary = accent)) {
                Scaffold { padding -> RelayScreen(Modifier.padding(padding)) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed.intValue++
    }

    @Composable
    private fun RelayScreen(modifier: Modifier) {
        val state by Watcher.state.collectAsStateWithLifecycle()
        var on by remember { mutableStateOf(WatcherService.isOn(this)) }
        // Re-read the permission and battery settings whenever the screen comes back.
        val tick = resumed.intValue
        val notificationsOk = remember(tick) {
            Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }
        val batteryOk = remember(tick) { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }
        var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(1_000)
                now = SystemClock.elapsedRealtime()
            }
        }

        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Claude Remote", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Your watch sends its Claude requests through this phone, and the phone tells the watch when Claude finishes or needs an approval.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Relay", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                !on -> "Off. The watch goes straight to your computers."
                                state.checkedAt == 0L -> "Starting…"
                                else -> "Checked ${(now - state.checkedAt) / 1000}s ago · ${state.relayed} watch requests relayed"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Switch(checked = on, onCheckedChange = {
                        on = it
                        WatcherService.set(this@MainActivity, it)
                    })
                }
            }

            if (on) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Computers", style = MaterialTheme.typography.titleMedium)
                        if (state.computers.isEmpty()) {
                            Text("None yet. Open Claude Remote on the watch once so it can share them.", style = MaterialTheme.typography.bodySmall)
                        }
                        state.computers.forEach { c ->
                            Text(
                                c.name + " · " + when {
                                    c.error != null -> c.error
                                    c.working > 0 -> "${c.working} working"
                                    else -> "reachable"
                                },
                                color = if (c.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        state.lastAlert?.let { Text("Last alert: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Setup", style = MaterialTheme.typography.titleMedium)
                    Text("Keep the Tailscale app connected, so this phone can reach your computers without the public link.", style = MaterialTheme.typography.bodySmall)
                    if (notificationsOk) {
                        Text("✓ Status notification allowed", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Button(onClick = { askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow the status notification") }
                    }
                    if (batteryOk) {
                        Text("✓ Allowed to run in the background", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Button(onClick = ::askBattery) { Text("Let it run in the background") }
                    }
                }
            }
        }
    }

    @SuppressLint("BatteryLife") // Relaying for the watch is exactly what the exemption is for.
    private fun askBattery() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }
}
