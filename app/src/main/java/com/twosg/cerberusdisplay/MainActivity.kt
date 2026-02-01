package com.twosg.cerberusdisplay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    val logLines = remember { mutableStateListOf<String>() }

    fun log(msg: String) {
        // keep last ~300 lines
        if (logLines.size > 300) logLines.removeAt(0)
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
                    // Pre-Android 12: dev.name is not gated by BLUETOOTH_CONNECT
                    dev.name ?: result.scanRecord?.deviceName
                }
                // Keep everything, but we’ll highlight Cerberus in UI via name substring
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
            status = "Bluetooth OFF (adapter reports disabled)"
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

        // Basic scan: no filters (robust), keep it simple for testbench
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

    // --- GATT connection manager (minimal, testbench-grade) ---
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
            status = "Missing permissions; cannot connect."
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
                    return
                }
                log("Services discovered. Locating notify characteristic…")

                // Find characteristics by UUID across all services.
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
                    return
                }
                log("Found notify characteristic. Enabling notifications…")

                // Enable local notification
                val okLocal = gatt.setCharacteristicNotification(notifyChar, true)
                if (!okLocal) {
                    log("ERROR: setCharacteristicNotification returned false")
                    return
                }

                // Enable CCCD on the peripheral
                val cccd = notifyChar.getDescriptor(CCCD_UUID)
                if (cccd == null) {
                    log("ERROR: CCCD descriptor missing on notify characteristic")
                    return
                }
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val okDesc = gatt.writeDescriptor(cccd)
                log("CCCD write initiated: $okDesc")

                // (Optional later) Keep rxChar for writes; for now we just listen.
                if (rxChar != null) {
                    log("Found RX characteristic (write) too.")
                } else {
                    log("RX characteristic not found (ok for testbench).")
                }
            }

            @Deprecated("Deprecated in API 33, but still works for our target")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val bytes = characteristic.value ?: return
                handleIncoming(bytes, ::log)
            }

            // Android 13+ callback (safe to include)
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
                    log("ERROR: CCCD write failed")
                }
            }
        }

        // TRANSPORT_LE helps some devices behave.
        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            dev.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            dev.connectGatt(context, false, callback)
        }
        gattHolder.value = gatt
    }

    // --- UI ---
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Cerberus BLE Testbench", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text("Status: $status")

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { startScan() }, enabled = !scanning) { Text("Scan") }
            OutlinedButton(onClick = { stopScan() }, enabled = scanning) { Text("Stop") }
            OutlinedButton(onClick = { permissionLauncher.launch(requiredPerms) }) { Text("Perms") }
            OutlinedButton(onClick = { disconnect() }, enabled = connectedAddr != null) { Text("Disconnect") }
        }

        Spacer(Modifier.height(12.dp))

        val sorted = hits.values
            .sortedWith(compareByDescending<ScanHit> { it.rssi }.thenBy { it.name ?: "" })

        Text("Tap a device to connect. (We’ll prefer name contains \"$TARGET_NAME_SUBSTR\".)")

        Spacer(Modifier.height(8.dp))

        // Devices list
        LazyColumn(
            modifier = Modifier.height(220.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sorted) { d ->
                val name = d.name ?: "(no name)"
                val isCerb = name.contains(TARGET_NAME_SUBSTR, ignoreCase = true)
                val isConnected = (connectedAddr == d.address)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // If user taps something non-Cerberus, still connect; it’s a bench.
                            connectTo(d.address)
                        }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = (if (isCerb) "🟢 " else "") + name + (if (isConnected) "  [CONNECTED]" else ""),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(d.address)
                        Text("RSSI: ${d.rssi} dBm")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Incoming packets:")
        Spacer(Modifier.height(6.dp))

        // Log view (simple)

        val lastLines = logLines.takeLast(20)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true
        ) {
            items(lastLines.asReversed()) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(2.dp))
            }
        }


    }
}

// --- Packet handling that matches your Python client logic ---
private fun handleIncoming(bytes: ByteArray, log: (String) -> Unit) {
    val ts = System.currentTimeMillis()
    val hex = bytes.joinToString(" ") { b -> "%02X".format(b.toInt() and 0xFF) }
    log("$ts  len=${bytes.size}  $hex")

    if (bytes.size != 20) return

    val b0 = bytes[0].toInt() and 0xFF

    // ACK packets: high bit set in first byte, matches your Python convention. :contentReference[oaicite:4]{index=4}
    if ((b0 and 0x80) != 0) {
        val cmd = b0 and 0x7F
        val status = bytes[1].toInt() and 0xFF
        log("  ACK: cmd=0x%02X status=0x%02X".format(cmd, status))
        return
    }

    // Status packet (packet_id==0) is used in your client. :contentReference[oaicite:5]{index=5}
    if (b0 == 0x00) {
        val status0 = bytes[1].toInt() and 0xFF
        val status1 = bytes[2].toInt() and 0xFF
        val mv = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val volts = mv / 1000.0
        log("  STATUS: v=%.3fV  status0=0x%02X status1=0x%02X".format(volts, status0, status1))
    }
}

/**
 * Android 12+: BLUETOOTH_SCAN/CONNECT.
 * Pre-12: location permission needed for scan results on many devices.
 */
private fun requiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
}
