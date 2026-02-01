package com.twosg.cerberusdisplay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import java.util.*

// --- Matches your Windows client constants ---
private const val TARGET_NAME_SUBSTR = "Cerberus"
private val CHAR_UUID: UUID = UUID.fromString("abcd1234-ab12-cd34-ef56-abcdef123456")
private val RX_CHAR_UUID: UUID = UUID.fromString("abcd5678-ab12-cd34-ef56-abcdef123456")

private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

data class ScanHit(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeenMs: Long
)

// --- UI Elements from new file ---

@Composable
fun CerberusGif(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                add(GifDecoder.Factory())
            }
            .build()
    }

    AsyncImage(
        model = R.drawable.cerberus,
        imageLoader = imageLoader,
        contentDescription = "Cerberus",
        modifier = modifier
    )
}

enum class DotStarAnim {
    SOLID,
    BREATHING,
    FLASH,
    URGENT
}

enum class DotStarMode(
    val label: String,
    val color: Color,
    val animation: DotStarAnim,
    val brightness: Float
) {
    GREEN_SOLID_DIM(
        "Idle",
        Color(0xFF00C853),
        DotStarAnim.SOLID,
        0.4f
    ),
    GREEN_BREATHING_DIM(
        "Connected",
        Color(0xFF00C853),
        DotStarAnim.BREATHING,
        0.4f
    ),
    AMBER_SOLID_DIM(
        "Connecting",
        Color(0xFFFFAB00),
        DotStarAnim.SOLID,
        0.4f
    ),
    BLUE_BT_FLASH_DIM(
        "Scanning",
        Color(0xFF2979FF),
        DotStarAnim.FLASH,
        0.4f
    ),
    RED_URGENT_BRIGHT(
        "Error",
        Color(0xFFD50000),
        DotStarAnim.URGENT,
        1.0f
    )
}

@Composable
fun DotStarIndicator(
    mode: DotStarMode,
    sizeDp: Dp = 64.dp
) {
    val infinite = rememberInfiniteTransition(label = "dotstar")

    val alpha = when (mode.animation) {
        DotStarAnim.SOLID -> mode.brightness

        DotStarAnim.BREATHING -> infinite.animateFloat(
            initialValue = 0.2f * mode.brightness,
            targetValue = mode.brightness,
            animationSpec = infiniteRepeatable(
                animation = tween(1800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "breathing"
        ).value

        DotStarAnim.FLASH -> infinite.animateFloat(
            initialValue = 0f,
            targetValue = mode.brightness,
            animationSpec = infiniteRepeatable(
                animation = tween(250),
                repeatMode = RepeatMode.Reverse
            ),
            label = "flash"
        ).value

        DotStarAnim.URGENT -> infinite.animateFloat(
            initialValue = 0f,
            targetValue = mode.brightness,
            animationSpec = infiniteRepeatable(
                animation = tween(120),
                repeatMode = RepeatMode.Reverse
            ),
            label = "urgent"
        ).value
    }

    Box(
        modifier = Modifier
            .size(sizeDp)
            .background(
                color = mode.color.copy(alpha = alpha),
                shape = CircleShape
            )
    )
}


class MainActivity : ComponentActivity() {

    private lateinit var btAdapter: BluetoothAdapter
    private var bleScanner: BluetoothLeScanner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        btAdapter = btManager.adapter
        bleScanner = btAdapter.bluetoothLeScanner

        setContent {
            MaterialTheme {
                CerberusBenchApp(
                    context = this,
                    btAdapterProvider = { btAdapter },
                    scannerProvider = { bleScanner }
                )
            }
        }
    }
}

@Composable
fun CerberusBenchApp(
    context: Context,
    btAdapterProvider: () -> BluetoothAdapter,
    scannerProvider: () -> BluetoothLeScanner?
) {
    val hits = remember { mutableStateMapOf<String, ScanHit>() }
    var scanning by remember { mutableStateOf(false) }
    var connectedAddr by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Idle") }
    val logLines = remember { mutableStateListOf("App started") }

    fun log(msg: String) {
        // keep last ~100 lines
        if (logLines.size > 100) logLines.removeAt(0)
        logLines.add(msg)
    }

    val requiredPerms = remember { requiredPermissions() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = requiredPerms.filter { results[it] != true }
        status = if (denied.isEmpty()) "Permissions granted" else "Missing: ${denied.joinToString()}"
        log(status)
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(requiredPerms)
    }

    fun hasAllPerms(): Boolean =
        requiredPerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val addr = dev.address ?: return

                val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val hasConnectPerm =
                        ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED

                    if (hasConnectPerm) dev.name ?: result.scanRecord?.deviceName
                    else result.scanRecord?.deviceName
                } else {
                    dev.name ?: result.scanRecord?.deviceName
                }
                hits[addr] = ScanHit(
                    address = addr,
                    name = name,
                    rssi = result.rssi,
                    lastSeenMs = System.currentTimeMillis()
                )
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
                status = "Scan failed: $errorCode"
                log(status)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        val adapter = btAdapterProvider()
        if (!adapter.isEnabled) {
            status = "Bluetooth OFF"
            log(status)
            return
        }
        if (!hasAllPerms()) {
            status = "Requesting permissions…"
            log(status)
            permissionLauncher.launch(requiredPerms)
            return
        }
        val scanner = scannerProvider()
        if (scanner == null) {
            status = "BLE scanner unavailable"
            log(status)
            return
        }

        hits.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanning = true
        status = "Scanning…"
        log(status)
        scanner.startScan(null, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scannerProvider()?.stopScan(scanCallback)
        scanning = false
        status = "Scan stopped"
        log(status)
    }

    val gattHolder = remember { mutableStateOf<BluetoothGatt?>(null) }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gattHolder.value?.let { g ->
            log("Disconnecting…")
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
        gattHolder.value = null
        connectedAddr = null
        status = "Disconnected"
        log(status)
    }

    @SuppressLint("MissingPermission")
    fun connectTo(address: String) {
        if (!hasAllPerms()) {
            status = "Missing permissions"
            log(status)
            return
        }
        val adapter = btAdapterProvider()
        val dev = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
        if (dev == null) {
            status = "Bad device address"
            log(status)
            return
        }

        disconnect() // close any previous
        status = "Connecting to $address…"
        log(status)
        connectedAddr = address

        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(gatt: BluetoothGatt, statusCode: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    log("GATT connected. Discovering services…")
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    log("GATT disconnected.")
                    status = "Disconnected"
                    runCatching { gatt.close() }
                    gattHolder.value = null
                    connectedAddr = null
                } else {
                    log("GATT state change: $newState (status=$statusCode)")
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, statusCode: Int) {
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    log("Service discovery failed: $statusCode")
                    status = "Service discovery failed"
                    return
                }
                log("Services discovered. Locating notify characteristic…")

                var notifyChar: BluetoothGattCharacteristic? = null
                var rxChar: BluetoothGattCharacteristic? = null

                for (svc in gatt.services) {
                    for (ch in svc.characteristics) {
                        if (ch.uuid == CHAR_UUID) notifyChar = ch
                        if (ch.uuid == RX_CHAR_UUID) rxChar = ch
                    }
                }

                if (notifyChar == null) {
                    log("ERROR: Notify characteristic not found: $CHAR_UUID")
                    status = "Notify char not found"
                    return
                }
                log("Found notify characteristic. Enabling notifications…")

                val okLocal = gatt.setCharacteristicNotification(notifyChar, true)
                if (!okLocal) {
                    log("ERROR: setCharacteristicNotification returned false")
                    status = "Notify setup failed (local)"
                    return
                }

                val cccd = notifyChar.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    log("ERROR: CCCD descriptor missing on notify characteristic")
                    status = "CCCD descriptor missing"
                    return
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(cccd)
                }
                log("CCCD write initiated")
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val bytes = characteristic.value ?: return
                handleIncoming(bytes, ::log)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                handleIncoming(value, ::log)
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                statusCode: Int
            ) {
                log("CCCD write complete: status=$statusCode")
                if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                    status = "Connected + Notifying"
                    log(status)
                } else {
                    status = "CCCD write failed"
                    log("ERROR: CCCD write failed")
                }
            }
        }

        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dev.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            dev.connectGatt(context, false, callback)
        }
        gattHolder.value = gatt
    }

    // --- UI ---
    val currentDotStarMode = when {
        status.contains("fail", ignoreCase = true) || status.contains("error", ignoreCase = true) -> DotStarMode.RED_URGENT_BRIGHT
        status.startsWith("Connecting") -> DotStarMode.AMBER_SOLID_DIM
        status.startsWith("Scanning") -> DotStarMode.BLUE_BT_FLASH_DIM
        status.startsWith("Connected") -> DotStarMode.GREEN_BREATHING_DIM
        else -> DotStarMode.GREEN_SOLID_DIM
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {

        // --- Status Header ---
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CerberusGif(modifier = Modifier.height(100.dp))
            Spacer(Modifier.height(12.dp))
            DotStarIndicator(currentDotStarMode)
            Spacer(Modifier.height(8.dp))
            Text(status, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(16.dp))

        // --- Controls ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)
        ) {
            Button(onClick = { startScan() }, enabled = !scanning) { Text("Scan") }
            OutlinedButton(onClick = { stopScan() }, enabled = scanning) { Text("Stop") }
            OutlinedButton(onClick = { disconnect() }, enabled = connectedAddr != null) { Text("Disconnect") }
        }

        Spacer(Modifier.height(16.dp))

        // --- Scan Results ---
        Text("Devices", style = MaterialTheme.typography.titleMedium)
        val sorted = hits.values
            .sortedWith(compareByDescending<ScanHit> { it.rssi }.thenBy { it.name ?: "" })

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp), // Use heightIn to be more flexible
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (hits.isEmpty() && scanning) {
                item { Text("Scanning...", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
            }
            items(sorted, key = { it.address }) { d ->
                val name = d.name ?: "(no name)"
                val isCerb = name.contains(TARGET_NAME_SUBSTR, ignoreCase = true)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { if (!scanning) connectTo(d.address) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isCerb) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyLarge)
                            Text(d.address, style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${d.rssi} dBm", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        Divider(modifier = Modifier.padding(vertical = 12.dp))

        // --- Log Output ---
        Text("Log", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true, // Show latest logs at the bottom
            verticalArrangement = Arrangement.Bottom
        ) {
            items(logLines.asReversed()) { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    softWrap = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}


// --- Helper Functions ---

fun requiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION // Still needed for companion devices
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}

fun handleIncoming(bytes: ByteArray, log: (String) -> Unit) {
    // TODO: Implement your data handling logic here
    log("RX: ${bytes.joinToString(" ") { "%02X".format(it) }}")
}

@Composable
private fun rememberRequiredPermissions(): Array<String> {
    return remember {
        requiredPermissions()
    }
}
