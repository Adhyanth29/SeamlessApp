package app.seamlessclip.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.seamlessclip.data.AppPrefs
import app.seamlessclip.data.PairingInfo
import app.seamlessclip.net.ClipEvent
import app.seamlessclip.net.ConnectionState

class MainActions(
    val onScan: () -> Unit,
    val onPasteLink: () -> Unit,
    val onUnpair: () -> Unit,
    val onSendClipboard: () -> Unit,
    val onReconnect: () -> Unit,
    val onStop: () -> Unit,
    val onAllowBackground: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: ConnectionState,
    history: List<ClipEvent>,
    pairedPcName: String?,
    prefs: AppPrefs,
    batteryUnrestricted: Boolean,
    actions: MainActions,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("SeamlessClip") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(state, pairedPcName, actions) }

            if (pairedPcName == null) {
                item { PairCard(actions) }
            } else {
                item { SendCard(actions) }
                if (!batteryUnrestricted) item { BatteryCard(actions) }
                item { SettingsCard(prefs) }
                item { HowToCard() }
                if (history.isNotEmpty()) {
                    item { SectionTitle("Recent") }
                    items(history) { HistoryRow(it) }
                }
                item { ManagePairingCard(pairedPcName, actions) }
            }
        }
    }
}

@Composable
private fun StatusCard(state: ConnectionState, pairedPcName: String?, actions: MainActions) {
    val (title, detail) = when (state) {
        is ConnectionState.Connected -> "Connected to ${state.pcName}" to "Copy on your PC and it's on this phone's clipboard."
        is ConnectionState.Connecting -> "Connecting to ${state.pcName}…" to state.host
        is ConnectionState.Disconnected -> "${state.pcName} not reachable" to
            "${state.reason}. Make sure the PC app is running and both devices are on the same Wi-Fi."
        ConnectionState.NotPaired -> "Not paired" to "Pair with your PC to start syncing."
        ConnectionState.Idle -> {
            if (pairedPcName == null) {
                "Not paired" to "Pair with your PC to start syncing."
            } else {
                "Sync stopped" to "Tap Reconnect to sync with $pairedPcName."
            }
        }
    }
    val connected = state is ConnectionState.Connected
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.padding(2.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            if (pairedPcName != null && !connected) {
                Row(Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = actions.onReconnect) { Text("Reconnect") }
                }
            }
        }
    }
}

@Composable
private fun PairCard(actions: MainActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Pair with your PC", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. Run SeamlessClip on your Windows PC (it lives in the system tray).\n" +
                    "2. Choose \"Pair a phone…\" from its tray menu.\n" +
                    "3. Scan the QR code it shows.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = actions.onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan pairing QR") }
            OutlinedButton(onClick = actions.onPasteLink, modifier = Modifier.fillMaxWidth()) { Text("Paste pairing link") }
        }
    }
}

@Composable
private fun SendCard(actions: MainActions) {
    Button(onClick = actions.onSendClipboard, modifier = Modifier.fillMaxWidth()) {
        Text("Send this phone's clipboard to PC")
    }
}

@Composable
private fun BatteryCard(actions: MainActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Keep the connection alive", style = MaterialTheme.typography.titleMedium)
            Text(
                "Battery optimisation can cut the link while the screen is off, so PC copies arrive late. " +
                    "Allowing unrestricted battery use keeps it instant.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = actions.onAllowBackground) { Text("Allow background use") }
        }
    }
}

@Composable
private fun SettingsCard(prefs: AppPrefs) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            PrefSwitch("Put PC copies on this phone's clipboard", prefs.applyFromPc) { prefs.applyFromPc = it }
            PrefSwitch("Notify when something arrives from PC", prefs.notifyOnReceive) { prefs.notifyOnReceive = it }
            PrefSwitch("Start automatically after reboot", prefs.startOnBoot) { prefs.startOnBoot = it }
        }
    }
}

@Composable
private fun PrefSwitch(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
    var checked by remember { mutableStateOf(initial) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = {
            checked = it
            onChange(it)
        })
    }
}

@Composable
private fun HowToCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Sending from phone to PC", style = MaterialTheme.typography.titleMedium)
            Text(
                "Android doesn't let apps read the clipboard in the background, so pick whichever is quickest:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Bullet("After copying, tap Share in the clipboard pop-up → \"Send to PC\".")
            Bullet("Select text → ⋮ → \"Send to PC\".")
            Bullet("Add the \"Clipboard → PC\" Quick Settings tile and tap it after copying.")
            Bullet("Use \"Send clipboard to PC\" on the SeamlessClip notification.")
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row {
        Text("•  ", style = MaterialTheme.typography.bodyMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun HistoryRow(event: ClipEvent) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (event.outgoing) "→ PC" else "← ${event.peer}",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(96.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            event.preview,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            DateUtils.getRelativeTimeSpanString(event.timeMillis).toString(),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ManagePairingCard(pcName: String, actions: MainActions) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Paired with $pcName", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onScan) { Text("Re-pair") }
                OutlinedButton(onClick = actions.onStop) { Text("Stop sync") }
                TextButton(onClick = actions.onUnpair) { Text("Unpair") }
            }
        }
    }
}

@Composable
fun PairingConfirmDialog(info: PairingInfo, replacing: String?, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pair with ${info.pcName}?") },
        text = {
            val replaceWarning = replacing?.let { "\n\nThis replaces your current pairing with $it." }.orEmpty()
            Text(
                "This phone will share its clipboard with the PC at ${info.hosts.joinToString(", ")} " +
                    "(port ${info.port}). Only continue if this code came from your own PC." + replaceWarning
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Pair") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
