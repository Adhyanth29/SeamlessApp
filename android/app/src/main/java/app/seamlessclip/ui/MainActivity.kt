package app.seamlessclip.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.seamlessclip.data.AppPrefs
import app.seamlessclip.data.PairingInfo
import app.seamlessclip.data.PairingStore
import app.seamlessclip.net.ConnectionState
import app.seamlessclip.net.SyncHub
import app.seamlessclip.service.SyncService
import app.seamlessclip.share.sendWithFeedback
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private lateinit var prefs: AppPrefs
    private lateinit var pairingStore: PairingStore

    private val pairedPcName = mutableStateOf<String?>(null)
    private val pendingPairing = mutableStateOf<PairingInfo?>(null)
    private var pendingFromClipboard = false
    private val batteryUnrestricted = mutableStateOf(true)

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::offerPairing)
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best effort */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = AppPrefs(this)
        pairingStore = PairingStore(this)
        refreshPairing()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (pairedPcName.value != null) SyncService.start(this)
        handleIntent(intent)

        setContent {
            SeamlessClipTheme {
                val state by SyncHub.state.collectAsStateWithLifecycle()
                val history by SyncHub.history.collectAsStateWithLifecycle()
                MainScreen(
                    state = state,
                    history = history,
                    pairedPcName = pairedPcName.value,
                    prefs = prefs,
                    batteryUnrestricted = batteryUnrestricted.value,
                    actions = MainActions(
                        onScan = ::startScan,
                        onPasteLink = ::pastePairingLink,
                        onUnpair = ::unpair,
                        onSendClipboard = ::sendClipboard,
                        onReconnect = { SyncService.start(this, SyncService.ACTION_RECONNECT) },
                        onStop = { SyncService.stop(this) },
                        onAllowBackground = ::requestBatteryExemption,
                    ),
                )
                pendingPairing.value?.let { info ->
                    PairingConfirmDialog(
                        info = info,
                        replacing = pairingStore.load()?.takeIf { it.serverId != info.serverId }?.pcName,
                        onConfirm = { confirmPairing(info) },
                        onDismiss = { pendingPairing.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        batteryUnrestricted.value = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (intent.action == Intent.ACTION_VIEW && data.scheme == PairingInfo.SCHEME) {
            offerPairing(data.toString())
        }
    }

    // ---- Pairing --------------------------------------------------------

    private fun startScan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("Scan the QR code shown by SeamlessClip on your PC")
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
    }

    private fun pastePairingLink() {
        val text = readClipboard()
        if (text.isNullOrBlank()) {
            toast("Copy the pairing link from the PC first")
        } else {
            offerPairing(text)
            pendingFromClipboard = pendingPairing.value != null
        }
    }

    /** Pairing links can arrive from untrusted places (any web page can fire a VIEW intent), so always confirm. */
    private fun offerPairing(link: String) {
        val info = PairingInfo.parse(link)
        if (info == null) {
            toast("That isn't a SeamlessClip pairing code")
            return
        }
        pendingFromClipboard = false
        pendingPairing.value = info
    }

    private fun confirmPairing(info: PairingInfo) {
        pendingPairing.value = null
        if (pendingFromClipboard) {
            // The link contains the pairing key; don't leave it on the clipboard (or sync it anywhere).
            getSystemService(ClipboardManager::class.java).clearPrimaryClip()
            pendingFromClipboard = false
        }
        pairingStore.save(info)
        refreshPairing()
        SyncHub.onPairingChanged()
        SyncService.start(this)
        toast("Paired with ${info.pcName}")
    }

    private fun unpair() {
        pairingStore.clear()
        refreshPairing()
        SyncHub.onPairingChanged()
        SyncService.stop(this)
        SyncHub.setState(ConnectionState.NotPaired)
    }

    private fun refreshPairing() {
        pairedPcName.value = pairingStore.load()?.pcName
    }

    // ---- Actions --------------------------------------------------------

    private fun sendClipboard() = sendWithFeedback(this, readClipboard())

    private fun readClipboard(): String? {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()
    }

    @SuppressLint("BatteryLife") // Sideloaded utility app; a persistent LAN link is its core function.
    private fun requestBatteryExemption() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(intent) }
            .onFailure { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
