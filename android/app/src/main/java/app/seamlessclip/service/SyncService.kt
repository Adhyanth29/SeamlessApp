package app.seamlessclip.service

import android.Manifest
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.seamlessclip.auto.AutoCopyStatus
import app.seamlessclip.auto.AutoCopyWatcher
import app.seamlessclip.auto.ClipboardGrabber
import app.seamlessclip.data.AppPrefs
import app.seamlessclip.data.PairingStore
import app.seamlessclip.net.BeaconListener
import app.seamlessclip.net.ClipEvent
import app.seamlessclip.net.ConnectionState
import app.seamlessclip.net.SyncClient
import app.seamlessclip.net.SyncHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the PC connection. Receiving from the PC works in the background
 * (writing the clipboard is allowed); sending needs a foreground moment (share sheet, tile, app).
 */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: AppPrefs
    private lateinit var pairingStore: PairingStore
    private lateinit var clipboard: ClipboardManager
    private var clientJob: Job? = null
    private var beaconJob: Job? = null
    private lateinit var autoCopy: AutoCopyWatcher
    private var clipListenerRegistered = false

    /** Last text auto-sent to the PC; avoids resending the same clip on repeated copy signals. */
    private var lastAutoSent: String? = null

    /**
     * Must be registered for ClipboardService to log a denial for us on every copy (that log line is
     * the background trigger). While our UI is in the foreground it is also called directly.
     */
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { onCopyDetected() }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = SyncHub.requestReconnect()
    }

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        pairingStore = PairingStore(this)
        clipboard = getSystemService(ClipboardManager::class.java)
        goForeground(SyncHub.state.value)
        autoCopy = AutoCopyWatcher(this, scope, ::onCopyDetected)
        reconcileAutoCopy(inForeground = false)

        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "Could not register network callback", it) }

        scope.launch { SyncHub.pairingVersion.collect { restartClient() } }
        scope.launch {
            SyncHub.state.combine(AutoCopyWatcher.status) { state, auto -> state to auto }
                .collect { (state, auto) ->
                    updateStatusNotification(state, auto)
                    manageBeacon(state)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Satisfy the startForegroundService() contract on every start, not just the first.
        goForeground(SyncHub.state.value)
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RECONNECT -> SyncHub.requestReconnect()
            ACTION_AUTO_COPY -> reconcileAutoCopy(intent.getBooleanExtra(EXTRA_IN_FOREGROUND, false))
        }
        return START_STICKY
    }

    private fun restartClient() {
        clientJob?.cancel()
        val pairing = pairingStore.load()
        SyncHub.pairedServerId = pairing?.serverId
        if (pairing == null) {
            SyncHub.setState(ConnectionState.NotPaired)
            stopSelf()
            return
        }
        val client = SyncClient(pairingStore, prefs, ::onClipFromPc)
        clientJob = scope.launch { client.runForever() }
    }

    private fun reconcileAutoCopy(inForeground: Boolean) {
        val enabled = prefs.autoSendFromPhone
        autoCopy.reconcile(enabled, inForeground)
        val wantListener = enabled && autoCopy.isRunning
        if (wantListener && !clipListenerRegistered) {
            clipboard.addPrimaryClipChangedListener(clipListener)
            clipListenerRegistered = true
        } else if (!wantListener && clipListenerRegistered) {
            clipboard.removePrimaryClipChangedListener(clipListener)
            clipListenerRegistered = false
        }
    }

    /** Something was copied somewhere on the phone: read it (briefly taking focus) and send it. */
    private fun onCopyDetected() {
        if (!prefs.autoSendFromPhone) return
        ClipboardGrabber.grab(this) { clip ->
            when {
                clip == null -> Log.d(TAG, "Copy detected but clipboard could not be read")
                clip.sensitive -> Log.i(TAG, "Not auto-sending a clip marked sensitive (e.g. password manager)")
                clip.text == SyncHub.lastAppliedFromPc -> SyncHub.lastAppliedFromPc = null // echo of a PC clip
                clip.text == lastAutoSent -> Unit
                else -> {
                    lastAutoSent = clip.text
                    SyncHub.sendText(this, clip.text)
                }
            }
        }
    }

    private fun onClipFromPc(pcName: String, text: String) {
        val preview = SyncHub.preview(text)
        SyncHub.record(ClipEvent(outgoing = false, peer = pcName, preview = preview, timeMillis = System.currentTimeMillis()))
        if (!prefs.applyFromPc) return

        scope.launch {
            try {
                SyncHub.lastAppliedFromPc = text
                lastAutoSent = null // the phone clipboard changed, so a re-copy of the old text is new again
                clipboard.setPrimaryClip(ClipData.newPlainText("From $pcName", text))
                if (prefs.notifyOnReceive && canNotify()) {
                    getSystemService(NotificationManager::class.java)
                        .notify(Notifications.ID_RECEIVED, Notifications.received(this@SyncService, pcName, preview))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not set clipboard", e)
            }
        }
    }

    private fun manageBeacon(state: ConnectionState) {
        val shouldListen = state is ConnectionState.Connecting || state is ConnectionState.Disconnected
        if (!shouldListen) {
            beaconJob?.cancel()
            beaconJob = null
        } else if (beaconJob?.isActive != true) {
            beaconJob = scope.launch {
                try {
                    BeaconListener.listen(this@SyncService)
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Beacon listener unavailable", e)
                }
            }
        }
    }

    private fun goForeground(state: ConnectionState) {
        try {
            ServiceCompat.startForeground(
                this, Notifications.ID_STATUS, Notifications.status(this, state),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // e.g. ForegroundServiceStartNotAllowedException when started from the background.
            Log.e(TAG, "Could not enter foreground", e)
            stopSelf()
        }
    }

    private fun updateStatusNotification(state: ConnectionState, auto: AutoCopyStatus) {
        if (!canNotify()) return
        getSystemService(NotificationManager::class.java)
            .notify(Notifications.ID_STATUS, Notifications.status(this, state, auto))
    }

    private fun canNotify() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        if (clipListenerRegistered) clipboard.removePrimaryClipChangedListener(clipListener)
        autoCopy.reconcile(enabled = false, inForeground = false)
        scope.cancel()
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        if (SyncHub.state.value !is ConnectionState.NotPaired) SyncHub.setState(ConnectionState.Idle)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SyncService"
        const val ACTION_STOP = "app.seamlessclip.action.STOP"
        const val ACTION_RECONNECT = "app.seamlessclip.action.RECONNECT"
        const val ACTION_AUTO_COPY = "app.seamlessclip.action.AUTO_COPY"
        const val EXTRA_IN_FOREGROUND = "in_foreground"

        /** Re-evaluate automatic sending; call with [inForeground] = true from a visible activity. */
        fun refreshAutoCopy(context: Context, inForeground: Boolean) =
            start(context, ACTION_AUTO_COPY) { putExtra(EXTRA_IN_FOREGROUND, inForeground) }

        fun start(context: Context, action: String? = null, extras: Intent.() -> Unit = {}) {
            val intent = Intent(context, SyncService::class.java).setAction(action).apply(extras)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.w(TAG, "Could not start service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }
    }
}
