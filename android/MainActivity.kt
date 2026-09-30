package com.example.garagedooropener

// =============================================================================
// JCS Garage Door Opener - Android client
//
// Talks to an ESPHome device three ways, in this order of preference:
//   LOCAL     https://<device ip>          (phone on a network that can reach it)
//   INTERNET  https://<public host>:<port> (from anywhere else)
//   BLUETOOTH BLE GATT write               (when WiFi is unavailable entirely)
//
// All three are optional. Whichever have complete credentials in the config are
// offered; the rest are hidden rather than shown failing. With only Bluetooth
// configured the app is a single button and no door status, because status
// comes from the web server.
//
// Design note: the web path POLLS. An earlier version held ESPHome's /events
// stream open, which meant every running copy permanently occupied one of the
// device's session slots - and it only has a handful. Short polls open a
// connection, read, and close, so a backgrounded phone costs the device nothing.
//
// The door state machine lives on the device, not here. This reads one value.
// =============================================================================

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

private const val APP_VERSION = "0.37"
private const val TAG = "GarageDoor"

private const val POLL_INTERVAL_MS = 1_000L
// How recently a poll must have succeeded for a press to be allowed through.
// A press during a network switch is refused outright rather than queued - it
// either happens now or not at all.
private const val PRESS_FRESHNESS_MS = 8_000L
private const val BLE_SCAN_TIMEOUT_MS = 10_000L
private const val BLE_CONNECT_TIMEOUT_MS = 15_000L
private const val MAX_LOG_LINES = 300

enum class ConnectionMode { NONE, LOCAL, INTERNET, BLUETOOTH }

private fun explainGattStatus(status: Int): String = when (status) {
    0 -> "success"
    8 -> "timed out (device likely out of range)"
    19 -> "device ended the connection"
    22 -> "connection ended locally"
    34 -> "link-layer timeout"
    133 -> "generic connection error (out of range, or Bluetooth busy - try again)"
    257 -> "internal Android Bluetooth error - toggling Bluetooth off/on may help"
    else -> "error code $status"
}

class MainActivity : ComponentActivity() {

    // ---- Configuration ----
    private var config by mutableStateOf<GarageConfig?>(null)
    private var showSetup by mutableStateOf(false)
    private var setupError by mutableStateOf<String?>(null)
    private var setupText by mutableStateOf("")
    private var net: Net? = null

    // ---- UI state ----
    private var connectionMode by mutableStateOf(ConnectionMode.NONE)
    private var doorStatusText by mutableStateOf("Checking...")
    private var connectionNote by mutableStateOf<String?>(null)
    private var bleLinkEnabled by mutableStateOf(true)
    private val eventLog = mutableStateListOf<String>()

    // ---- Preferences ----
    private lateinit var prefs: Prefs
    private var useSlider by mutableStateOf(true)
    private var showLogs by mutableStateOf(false)

    // ---- Polling ----
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false
    private val pollsInFlight = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var pollGeneration = 0
    @Volatile private var activeBaseUrl: String? = null
    @Volatile private var lastPollSuccessAt = 0L

    // ---- Bluetooth ----
    private var bluetoothGatt: BluetoothGatt? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var bleConnectAttempt = 0

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val logLock = Any()

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (results.values.all { it }) {
                startBleScan()
            } else {
                log("Bluetooth permission denied.")
                connectionNote = "Bluetooth permission is required."
                bleLinkEnabled = true
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startBleScan()
            else {
                connectionNote = "Bluetooth is turned off."
                bleLinkEnabled = true
            }
        }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        scanner = bluetoothAdapter?.bluetoothLeScanner

        prefs = Prefs(this)
        useSlider = prefs.useSlider
        showLogs = prefs.showLogs

        loadConfig()

        setContent {
            MaterialTheme {
                CappedFontScale {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val cfg = config
                        if (showSetup || cfg == null) {
                            SetupScreen(
                                text = setupText,
                                error = setupError,
                                isFirstRun = cfg == null,
                                currentSummary = cfg?.summary(),
                                onTextChange = { setupText = it; setupError = null },
                                onUseExample = { setupText = ConfigStore.EXAMPLE; setupError = null },
                                onSave = { saveConfig(setupText) },
                                onCancel = if (cfg != null) ({ showSetup = false }) else null,
                                useSlider = useSlider,
                                onUseSliderChange = { useSlider = it; prefs.useSlider = it },
                                showLogs = showLogs,
                                onShowLogsChange = { showLogs = it; prefs.showLogs = it }
                            )
                        } else {
                            GarageDoorScreen(
                                config = cfg,
                                connectionMode = connectionMode,
                                doorStatusText = doorStatusText,
                                connectionNote = connectionNote,
                                bleLinkEnabled = bleLinkEnabled,
                                eventLog = eventLog,
                                onRetry = { retryNow() },
                                onBluetoothLink = { onBluetoothLinkTapped() },
                                onPress = { pressButton() },
                                onCopyLog = { copyLogToClipboard() },
                                onOpenSettings = {
                                    setupText = ConfigStore.rawText(this)
                                    setupError = null
                                    showSetup = true
                                },
                                useSlider = useSlider,
                                showLogs = showLogs,
                                onToggleLogs = { showLogs = !showLogs; prefs.showLogs = showLogs }
                            )
                        }
                    }
                }
            }
        }
    }

    // Polling runs only while the app is on screen. Nothing is left connected in
    // the background, so a phone in a pocket costs the device nothing.
    override fun onStart() {
        super.onStart()
        if (config?.hasWeb == true && connectionMode != ConnectionMode.BLUETOOTH) {
            startPolling("App opened")
        }
    }

    override fun onStop() {
        super.onStop()
        stopPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
        closeBle()
    }

    // =========================================================================
    // Configuration
    // =========================================================================

    private fun loadConfig() {
        try {
            val loaded = ConfigStore.load(this)
            if (loaded == null) {
                log("No configuration found. Paste one to get started.")
                setupText = ""
                showSetup = true
                return
            }
            applyConfig(loaded)
            log("Configuration loaded: ${loaded.summary()}")
        } catch (e: ConfigError) {
            // Saved config exists but is broken - say so plainly rather than
            // starting up half configured.
            log("Saved configuration is invalid: ${e.message}")
            setupText = ConfigStore.rawText(this)
            setupError = e.message
            showSetup = true
        }
    }

    private fun applyConfig(cfg: GarageConfig) {
        config = cfg
        net = cfg.web?.let { Net(it) }
    }

    private fun saveConfig(json: String) {
        try {
            val cfg = ConfigStore.save(this, json)

            // Drop anything belonging to the old config before switching over.
            stopPolling()
            closeBle()
            activeBaseUrl = null
            lastPollSuccessAt = 0L
            connectionMode = ConnectionMode.NONE
            connectionNote = null

            applyConfig(cfg)
            showSetup = false
            setupError = null
            log("Configuration saved: ${cfg.summary()}")

            if (cfg.hasWeb) startPolling("Configuration saved")
        } catch (e: ConfigError) {
            setupError = e.message
        } catch (e: Exception) {
            setupError = "Could not save: ${e.message}"
        }
    }

    // =========================================================================
    // Logging
    // =========================================================================

    private fun log(message: String) {
        synchronized(logLock) {
            val stamped = "${timeFormat.format(System.currentTimeMillis())}  $message"
            Log.d(TAG, message)
            runOnUiThread {
                eventLog.add(0, stamped)
                while (eventLog.size > MAX_LOG_LINES) eventLog.removeAt(eventLog.size - 1)
            }
        }
    }

    private fun copyLogToClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("Garage Door Log", eventLog.joinToString("\n"))
        )
        log("Copied ${eventLog.size} log lines to clipboard.")
    }

    // =========================================================================
    // Web polling
    // =========================================================================

    /** True if this phone is on a network from which the local address is reachable. */
    private fun isOnLocalSubnet(): Boolean {
        val prefixes = config?.web?.localSubnets ?: return false
        if (prefixes.isEmpty()) return false
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val props = cm.activeNetwork?.let { cm.getLinkProperties(it) }
            props?.linkAddresses?.any { linkAddress ->
                val ip = linkAddress.address.hostAddress ?: return@any false
                prefixes.any { ip.startsWith(it) }
            } ?: false
        } catch (e: Exception) {
            log("Couldn't read network info: ${e.message}")
            false
        }
    }

    private fun modeFor(baseUrl: String) =
        if (baseUrl == config?.web?.localUrl) ConnectionMode.LOCAL else ConnectionMode.INTERNET

    /** Addresses to try, best guess first. Only those actually configured. */
    private fun candidateUrls(): List<String> {
        val web = config?.web ?: return emptyList()
        activeBaseUrl?.let { return listOf(it) }
        val local = web.localUrl
        val internet = web.internetUrl
        return when {
            local != null && internet != null ->
                if (isOnLocalSubnet()) listOf(local, internet) else listOf(internet, local)
            local != null -> listOf(local)
            internet != null -> listOf(internet)
            else -> emptyList()
        }
    }

    private fun startPolling(reason: String) {
        if (polling) return
        if (config?.hasWeb != true) return
        polling = true
        pollGeneration++
        activeBaseUrl = null
        lastPollSuccessAt = 0L
        // Discard any status from a previous session - it may be hours old.
        doorStatusText = "Unknown"
        log("$reason - starting to poll.")
        handler.post(pollRunnable)
    }

    private fun stopPolling() {
        if (!polling) return
        polling = false
        handler.removeCallbacks(pollRunnable)
        log("Stopped polling.")
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollOnce()
            if (polling) handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private fun retryNow() {
        if (config?.hasWeb != true) return
        // Bumping the generation orphans anything already in flight, so a poll
        // stuck on a dead address can't come back later and clobber this one.
        pollGeneration++
        activeBaseUrl = null
        log("Retry requested - checking configured addresses.")
        runOnUiThread {
            connectionNote = "Checking..."
            doorStatusText = "Checking..."
        }
        if (!polling) startPolling("Retry") else pollOnce(force = true)
    }

    private fun pollOnce(force: Boolean = false) {
        if (connectionMode == ConnectionMode.BLUETOOTH) return
        if (config?.hasWeb != true) return
        // Routine polls skip if one is already running. A Retry goes ahead
        // anyway - that's the point of pressing it. The cap stops frantic
        // tapping spawning unbounded threads.
        if (!force && pollsInFlight.get() > 0) return
        if (force && pollsInFlight.get() >= 3) {
            log("Retry ignored - too many checks already running.")
            return
        }

        val myGeneration = pollGeneration
        pollsInFlight.incrementAndGet()

        Thread {
            try {
                var connected = false
                for (baseUrl in candidateUrls()) {
                    if (myGeneration != pollGeneration) return@Thread
                    if (force) log("Trying ${modeFor(baseUrl)}...")

                    val status = fetchDoorStatus(baseUrl, verbose = force)

                    if (myGeneration != pollGeneration) return@Thread
                    if (status != null) {
                        lastPollSuccessAt = System.currentTimeMillis()
                        if (activeBaseUrl != baseUrl) {
                            activeBaseUrl = baseUrl
                            log("Connected via ${modeFor(baseUrl)}")
                        }
                        runOnUiThread {
                            connectionMode = modeFor(baseUrl)
                            connectionNote = null
                            doorStatusText = status
                        }
                        connected = true
                        break
                    }
                }

                if (!connected && myGeneration == pollGeneration) {
                    activeBaseUrl = null
                    lastPollSuccessAt = 0L
                    if (force) log("Retry failed - no configured address responded.")
                    runOnUiThread {
                        connectionMode = ConnectionMode.NONE
                        doorStatusText = "Unknown"
                        connectionNote = "No response from the garage door."
                    }
                }
            } finally {
                pollsInFlight.decrementAndGet()
            }
        }.start()
    }

    /** Returns the door status string, or null if the device didn't answer. */
    private fun fetchDoorStatus(baseUrl: String, verbose: Boolean = false): String? {
        val n = net ?: return null
        val web = config?.web ?: return null
        return try {
            val request = Request.Builder()
                .url("$baseUrl/text_sensor/${web.doorStatusId}")
                .header("Authorization", n.authHeader())
                .get()
                .build()

            n.clientFor(baseUrl).newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    log("Status request to ${modeFor(baseUrl)} returned HTTP ${response.code}.")
                    return null
                }
                val body = response.body?.string() ?: return null
                val json = JSONObject(body)
                val state = json.optString("state", "").ifEmpty { json.optString("value", "") }
                state.ifEmpty { null }
            }
        } catch (e: Exception) {
            // Normally quiet: probing the address we aren't on is expected to
            // fail. On a Retry, say why - a silent failure is what made this
            // feel broken before.
            if (verbose || activeBaseUrl != null) {
                log("${modeFor(baseUrl)} failed: ${e.message ?: e.javaClass.simpleName}")
            }
            null
        }
    }

    private fun pressButtonViaWeb() {
        val n = net ?: return
        val web = config?.web ?: return
        val baseUrl = activeBaseUrl ?: return

        // Don't send on a connection that only looks alive. A poll that is
        // itself timing out leaves the UI showing "Connected" for several
        // seconds after the network has actually gone.
        val age = System.currentTimeMillis() - lastPollSuccessAt
        if (age > PRESS_FRESHNESS_MS) {
            log("Press cancelled - last contact was ${age}ms ago.")
            runOnUiThread {
                connectionMode = ConnectionMode.NONE
                connectionNote = "Not connected - press cancelled."
            }
            return
        }

        Thread {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/button/${web.pressButtonId}/press")
                    .header("Authorization", n.authHeader())
                    .post(ByteArray(0).toRequestBody(null))
                    .build()

                n.clientFor(baseUrl).newCall(request).execute().use { response ->
                    log("Button press sent, device replied HTTP ${response.code}.")
                    if (!response.isSuccessful) {
                        runOnUiThread {
                            connectionNote = "Device rejected the press (HTTP ${response.code})."
                        }
                    }
                }
            } catch (e: Exception) {
                log("Button press failed: ${e.message}")
                runOnUiThread { connectionNote = "Press failed - not sent. Try again." }
            }
        }.start()
    }

    // =========================================================================
    // Bluetooth
    // =========================================================================

    private fun onBluetoothLinkTapped() {
        val cfg = config ?: return
        if (!cfg.hasBluetooth) return

        if (connectionMode == ConnectionMode.BLUETOOTH) {
            // Leave the BLE link alone; just go back to trying the network.
            if (!cfg.hasWeb) return
            log("Switching back to network.")
            runOnUiThread { connectionMode = ConnectionMode.NONE }
            startPolling("Back to network")
            return
        }
        stopPolling()
        bleLinkEnabled = false
        if (!hasPermissions()) {
            permissionLauncher.launch(requiredPermissions)
            return
        }
        startBleScan()
    }

    private fun hasPermissions() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startBleScan() {
        val ble = config?.bluetooth ?: return
        val adapter = bluetoothAdapter
        if (adapter == null) {
            connectionNote = "This phone has no Bluetooth."
            bleLinkEnabled = true
            return
        }
        if (!adapter.isEnabled) {
            @Suppress("MissingPermission")
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        val bleScanner = scanner ?: run {
            connectionNote = "Bluetooth scanner unavailable."
            bleLinkEnabled = true
            return
        }
        if (scanning) return

        log("Scanning for '${ble.deviceName}'...")

        // Filter by name, not service UUID: a 128-bit UUID doesn't fit in the
        // 31-byte BLE advertisement, so ESPHome doesn't advertise it.
        val filters = listOf(ScanFilter.Builder().setDeviceName(ble.deviceName).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

        try {
            scanning = true
            @Suppress("MissingPermission")
            bleScanner.startScan(filters, settings, scanCallback)
        } catch (e: SecurityException) {
            scanning = false
            connectionNote = "Missing permission to scan."
            bleLinkEnabled = true
            return
        }

        handler.postDelayed({
            if (scanning) {
                scanning = false
                try {
                    @Suppress("MissingPermission")
                    bleScanner.stopScan(scanCallback)
                } catch (_: SecurityException) { }
                log("Bluetooth scan timed out.")
                connectionNote = "Bluetooth: no device found nearby."
                bleLinkEnabled = true
            }
        }, BLE_SCAN_TIMEOUT_MS)
    }

    private val scanCallback = object : ScanCallback() {
        @Suppress("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!scanning) return
            scanning = false
            scanner?.stopScan(this)
            log("Found device, signal ${result.rssi} dBm. Connecting...")

            bleConnectAttempt++
            val attempt = bleConnectAttempt
            bluetoothGatt = result.device.connectGatt(this@MainActivity, false, gattCallback)

            handler.postDelayed({
                if (attempt == bleConnectAttempt && connectionMode != ConnectionMode.BLUETOOTH) {
                    log("Bluetooth connect timed out.")
                    connectionNote = "Bluetooth: device stopped responding."
                    closeBle()
                    bleLinkEnabled = true
                }
            }, BLE_CONNECT_TIMEOUT_MS)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            log("Bluetooth scan failed (code $errorCode).")
            connectionNote = "Bluetooth scan failed."
            bleLinkEnabled = true
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @Suppress("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Ask for a bigger packet up front: the secret may not fit in
                    // the 20-byte default, and splitting it across packets proved
                    // unreliable on repeat presses.
                    if (!gatt.requestMtu(185)) gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("Bluetooth disconnected (${explainGattStatus(status)}).")
                    closeBle()
                    runOnUiThread {
                        bleLinkEnabled = true
                        if (connectionMode == ConnectionMode.BLUETOOTH) {
                            connectionMode = ConnectionMode.NONE
                        }
                    }
                    if (config?.hasWeb == true) startPolling("Bluetooth dropped")
                }
            }
        }

        @Suppress("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            log("Bluetooth packet size negotiated to $mtu bytes.")
            gatt.discoverServices()
        }

        @Suppress("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val ble = config?.bluetooth ?: return
            val characteristic = gatt.getService(ble.serviceUuid)
                ?.getCharacteristic(ble.triggerUuid)
            if (status != BluetoothGatt.GATT_SUCCESS || characteristic == null) {
                log("Bluetooth: expected service not found on this device.")
                connectionNote = "Bluetooth: this isn't the right device, or the UUIDs don't match."
                closeBle()
                runOnUiThread { bleLinkEnabled = true }
                return
            }
            log("Bluetooth ready.")
            runOnUiThread {
                connectionMode = ConnectionMode.BLUETOOTH
                connectionNote = null
                bleLinkEnabled = true
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            log(
                if (status == BluetoothGatt.GATT_SUCCESS) "Bluetooth press acknowledged."
                else "Bluetooth press failed (${explainGattStatus(status)})."
            )
        }
    }

    private fun pressButtonViaBle() {
        val ble = config?.bluetooth ?: return
        val gatt = bluetoothGatt ?: return
        // Re-fetch rather than caching: reusing a stale characteristic object
        // across repeated writes was unreliable on older Android versions.
        val characteristic = gatt.getService(ble.serviceUuid)
            ?.getCharacteristic(ble.triggerUuid) ?: run {
            connectionNote = "Lost the Bluetooth connection. Reconnect."
            return
        }
        val bytes = ble.secret.toByteArray(Charsets.UTF_8)
        try {
            @Suppress("MissingPermission")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                )
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            log("Bluetooth press sent.")
        } catch (e: SecurityException) {
            log("Bluetooth press blocked: ${e.message}")
        }
    }

    private fun closeBle() {
        try {
            @Suppress("MissingPermission")
            bluetoothGatt?.close()
        } catch (_: SecurityException) { }
        bluetoothGatt = null
    }

    // =========================================================================
    // Shared
    // =========================================================================

    private fun pressButton() {
        when (connectionMode) {
            ConnectionMode.LOCAL, ConnectionMode.INTERNET -> pressButtonViaWeb()
            ConnectionMode.BLUETOOTH -> pressButtonViaBle()
            ConnectionMode.NONE -> {
                connectionNote = if (config?.hasBluetooth == true) {
                    "Not connected. Try [Retry], or connect via Bluetooth."
                } else {
                    "Not connected. Try [Retry]."
                }
            }
        }
    }
}

// =============================================================================
// UI
// =============================================================================

private fun connectionLabel(mode: ConnectionMode) = when (mode) {
    ConnectionMode.LOCAL -> "Connected (Local Wi-Fi)"
    ConnectionMode.INTERNET -> "Connected (Internet)"
    ConnectionMode.BLUETOOTH -> "Connected (Bluetooth)"
    ConnectionMode.NONE -> "Not connected"
}

/**
 * Caps how far the system font-scale setting stretches this app's text.
 *
 * Android lets the user enlarge text system-wide, which is a genuine
 * accessibility feature - but this layout has fixed-height controls, so at large
 * settings the text overflows them and the screen looks broken.
 *
 * Text still grows with the user's preference, just not past the point where the
 * layout falls apart. Anyone who has chosen larger text still gets larger text.
 *
 * Note this only caps FONT scale. If the whole display size is increased, the
 * app scales proportionally, which looks fine.
 */
@Composable
private fun CappedFontScale(
    maxScale: Float = 1.25f,
    content: @Composable () -> Unit,
) {
    val current = LocalDensity.current
    val capped = if (current.fontScale > maxScale) {
        Density(current.density, maxScale)
    } else {
        current
    }
    CompositionLocalProvider(LocalDensity provides capped, content = content)
}

/**
 * Slide-to-confirm, as an alternative to a plain button.
 *
 * Exists because a stray tap can open a garage door. The thumb must be dragged
 * most of the way across before it fires; anything short of that snaps back and
 * does nothing. After a successful slide it holds at the far end briefly so the
 * gesture is visibly acknowledged, then resets.
 */
@Composable
fun SlideToConfirm(
    enabled: Boolean,
    label: String,
    onConfirm: () -> Unit,
) {
    val density = LocalDensity.current
    val trackHeight = 64.dp
    val thumbWidth = 72.dp
    val thumbPx = with(density) { thumbWidth.toPx() }

    var trackWidthPx by remember { mutableStateOf(0f) }
    var offsetX by remember { mutableStateOf(0f) }
    var confirmed by remember { mutableStateOf(false) }

    val maxOffset = (trackWidthPx - thumbPx).coerceAtLeast(0f)

    // Reset shortly after a successful slide, so the completed state is visible
    // rather than snapping back instantly.
    LaunchedEffect(confirmed) {
        if (confirmed) {
            delay(700)
            offsetX = 0f
            confirmed = false
        }
    }

    // Any change to enabled (e.g. the connection dropping) returns the thumb to
    // the start, so a half-completed drag can't be left sitting there.
    LaunchedEffect(enabled) {
        if (!enabled) {
            offsetX = 0f
            confirmed = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(trackHeight)
            .clip(RoundedCornerShape(trackHeight / 2))
            .background(if (enabled) Color(0xFFE3E3E8) else Color(0xFFF2F2F4))
            .onSizeChanged { trackWidthPx = it.width.toFloat() }
    ) {
        Text(
            if (confirmed) "Sent" else label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) Color(0xFF555560) else Color(0xFFAAAAB0),
            modifier = Modifier.align(Alignment.Center)
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .width(thumbWidth)
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary else Color(0xFFCCCCD2)
                )
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = enabled && !confirmed,
                    state = rememberDraggableState { delta ->
                        offsetX = (offsetX + delta).coerceIn(0f, maxOffset)
                    },
                    onDragStopped = {
                        // 90% of the way counts as a deliberate slide; short of
                        // that it snaps back and nothing happens.
                        if (maxOffset > 0f && offsetX >= maxOffset * 0.9f) {
                            offsetX = maxOffset
                            confirmed = true
                            onConfirm()
                        } else {
                            offsetX = 0f
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (confirmed) "\u2713" else "\u203A\u203A",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
fun SetupScreen(
    text: String,
    error: String?,
    isFirstRun: Boolean,
    currentSummary: String?,
    onTextChange: (String) -> Unit,
    onUseExample: () -> Unit,
    onSave: () -> Unit,
    onCancel: (() -> Unit)?,
    useSlider: Boolean,
    onUseSliderChange: (Boolean) -> Unit,
    showLogs: Boolean,
    onShowLogsChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            if (isFirstRun) "SET UP" else "SETTINGS",
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 3.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(12.dp))

        Text(
            if (isFirstRun) {
                "No configuration found. Paste your settings below.\n\n" +
                    "Any one of Local, Internet or Bluetooth is enough - leave out " +
                    "whichever you don't use and it won't appear in the app."
            } else {
                "Currently configured: ${currentSummary ?: "nothing"}\n\n" +
                    "Note: the Bluetooth secret must match the device. If you change it " +
                    "here, the device needs reflashing with the matching value, and " +
                    "Bluetooth won't work until both agree. The same applies to the " +
                    "certificate pin if the device's certificate is regenerated."
            },
            fontSize = 13.sp
        )

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                error,
                fontSize = 13.sp,
                color = Color(0xFFC62828),
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            label = { Text("Configuration (JSON)") },
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
        )

        Spacer(Modifier.height(12.dp))

        Row {
            Button(onClick = onSave, modifier = Modifier.weight(1f)) {
                Text("Save")
            }
            if (onCancel != null) {
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Cancel")
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // Preferences, kept separate from the credentials above - they are
        // personal choices, not device settings, and don't travel with a config
        // pasted onto someone else's phone.
        Text("Options", fontSize = 15.sp, fontWeight = FontWeight.Bold)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = useSlider, onCheckedChange = onUseSliderChange)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Slide to activate", fontSize = 14.sp)
                Text(
                    "Requires a deliberate slide instead of a tap, so the door " +
                        "can't be opened by a stray touch.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = showLogs, onCheckedChange = onShowLogsChange)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Show activity log", fontSize = 14.sp)
                Text(
                    "Diagnostic detail on the main screen. Can also be toggled there.",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "Paste example template",
            fontSize = 13.sp,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { onUseExample() }
        )

        Spacer(Modifier.height(20.dp))
        Text(
            "JCS Garage Door Opener version $APP_VERSION",
            fontSize = 12.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun GarageDoorScreen(
    config: GarageConfig,
    connectionMode: ConnectionMode,
    doorStatusText: String,
    connectionNote: String?,
    bleLinkEnabled: Boolean,
    eventLog: List<String>,
    onRetry: () -> Unit,
    onBluetoothLink: () -> Unit,
    onPress: () -> Unit,
    onCopyLog: () -> Unit,
    onOpenSettings: () -> Unit,
    useSlider: Boolean,
    showLogs: Boolean,
    onToggleLogs: () -> Unit,
) {
    val connected = connectionMode != ConnectionMode.NONE
    val onBluetooth = connectionMode == ConnectionMode.BLUETOOTH

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "GARAGE DOOR",
                fontSize = 32.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 4.sp
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    connectionLabel(connectionMode),
                    fontSize = 14.sp,
                    color = if (connected) Color(0xFF2E7D32) else Color.Black
                )
                // Retry only makes sense if there is a web address to retry.
                if (config.hasWeb) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "[Retry]",
                        fontSize = 14.sp,
                        textDecoration = TextDecoration.Underline,
                        modifier = Modifier.clickable { onRetry() }
                    )
                }
            }

            if (config.hasBluetooth) {
                Spacer(Modifier.height(4.dp))
                val bleIsStatic = onBluetooth && !config.hasWeb
                Text(
                    when {
                        onBluetooth && config.hasWeb -> "Switch back to network"
                        onBluetooth -> "Connected via Bluetooth"
                        else -> "Connect via Bluetooth"
                    },
                    fontSize = 14.sp,
                    textDecoration = if (bleIsStatic) TextDecoration.None else TextDecoration.Underline,
                    color = if (bleLinkEnabled) Color.Black else Color.Gray,
                    modifier = Modifier.clickable(enabled = bleLinkEnabled && !bleIsStatic) {
                        onBluetoothLink()
                    }
                )
            }

            // Door status comes from the web server. With Bluetooth only, or
            // while connected over Bluetooth, there is nothing live to show - so
            // the line is hidden rather than left showing something stale.
            if (config.hasWeb && !onBluetooth) {
                // Only show a real status while actually connected. Otherwise the
                // last value received is stale and we have no idea whether it is
                // still true - reopening the app after leaving it mid-travel used
                // to show "Partly Open" as though it were current.
                //
                // Grey rather than black, so an unconfirmed reading is visibly
                // different from a live one at a glance.
                val statusKnown = connectionMode == ConnectionMode.LOCAL ||
                    connectionMode == ConnectionMode.INTERNET
                Spacer(Modifier.height(24.dp))
                Text(
                    if (statusKnown) "Door Status: $doorStatusText" else "Door Status: Unknown",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (statusKnown) Color.Unspecified else Color.Gray
                )
            }

            Spacer(Modifier.height(24.dp))
            if (useSlider) {
                SlideToConfirm(
                    enabled = connected,
                    label = "Slide to Activate",
                    onConfirm = onPress
                )
            } else {
                Button(
                    onClick = onPress,
                    enabled = connected,
                    // heightIn, not height: lets the button grow if the label
                    // wraps at a larger font scale rather than clipping it.
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                ) {
                    Text("Press Button", fontSize = 18.sp)
                }
            }

            if (connectionNote != null) {
                Spacer(Modifier.height(12.dp))
                Text(connectionNote, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }

        // The log is a diagnostic tool, hidden by default so the main screen
        // reads as a door opener rather than a console. Collapsing it also frees
        // the vertical space, which matters on a small screen.
        Spacer(Modifier.height(24.dp))

        Text(
            "[Settings]",
            fontSize = 13.sp,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { onOpenSettings() }
        )

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (showLogs) "\u25BE Activity log" else "\u25B8 Activity log",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { onToggleLogs() }
            )
            if (showLogs) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "[Copy all]",
                    fontSize = 13.sp,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { onCopyLog() }
                )
            }
        }

        if (showLogs) {
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(1.dp, Color.LightGray, RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                SelectionContainer {
                    LazyColumn(state = rememberLazyListState(), modifier = Modifier.fillMaxSize()) {
                        items(eventLog) { entry ->
                            Text(entry, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        } else {
            Spacer(Modifier.weight(1f))
        }

        Text(
            "JCS Garage Door Opener version $APP_VERSION",
            fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
    }
}
