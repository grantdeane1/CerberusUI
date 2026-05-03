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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

//region --- Top-Level Constants ---

private const val TARGET_NAME_SUBSTR = "Cerberus"

object AlertConfig {
    const val FLOW_ALERT_PERIOD_MS = 500
    const val FLOW_ALERT_ON_MS = 165
    val FLOW_ALERT_COLOR = Color(0xFFD50000)
    const val ALARM_SNOOZE_MS = 120_000   // 2 minutes; adjust for clinical preference
}
private val CHAR_UUID: UUID = UUID.fromString("abcd1234-ab12-cd34-ef56-abcdef123456")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

//endregion

//region --- BLE Event Sealed Interface ---

sealed interface BleEvent {
    data class ScanResultReceived(val result: ScanResult) : BleEvent
    data class ScanFailed(val errorCode: Int) : BleEvent
    data class ConnectionStateChanged(val gatt: BluetoothGatt, val status: Int, val newState: Int) : BleEvent
    data class ServicesDiscovered(val gatt: BluetoothGatt, val status: Int) : BleEvent
    data class DescriptorWriteComplete(val gatt: BluetoothGatt, val descriptor: BluetoothGattDescriptor, val status: Int) : BleEvent
    data class CharacteristicChanged(val value: ByteArray) : BleEvent {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as CharacteristicChanged
            return value.contentEquals(other.value)
        }
        override fun hashCode(): Int = value.contentHashCode()
    }
    data class RssiRead(val rssi: Int, val status: Int) : BleEvent
}

//endregion

//region --- Data & State Classes ---

data class ScanHit(
    val address: String,
    val name: String?,
    val rssi: Int,
    val lastSeenMs: Long
)

enum class SensorMode {
    Normal, Occlusion, OverInfusion, ReverseFlow, Reserved;
    companion object {
        fun from(value: Int) = when (value) {
            0 -> Normal; 1 -> Occlusion; 2 -> OverInfusion; 3 -> ReverseFlow; else -> Reserved
        }
    }
}

enum class SensorHealth {
    Therm1Bad, Therm2Bad, Therm3Bad, Therm4Bad, Therm5Bad, Therm6Bad, HeaterBad, AllOK;
    companion object {
        fun from(value: Int) = when (value) {
            0 -> Therm1Bad; 1 -> Therm2Bad; 2 -> Therm3Bad; 3 -> Therm4Bad
            4 -> Therm5Bad; 5 -> Therm6Bad; 6 -> HeaterBad; 7 -> AllOK; else -> AllOK
        }
    }
}

enum class CerberusMode {
    FSM, TCM, CRM, CWM, WM, Reserved;
    companion object {
        fun from(value: Int) = when (value) {
            0 -> FSM; 1 -> TCM; 2 -> CRM; 3 -> CWM; 4 -> WM; else -> Reserved
        }
    }
}

data class CerberusStatus(
    val sensorMode: SensorMode,
    val isBatteryLow: Boolean,
    val isBleDisconnected: Boolean,
    val sensorHealth: SensorHealth,
    val isTempOutOfRange: Boolean,
    val cerberusMode: CerberusMode,
    val voltageMv: Int
)

sealed class ParseResult {
    data class Success(val status: CerberusStatus) : ParseResult()
    data class Error(val message: String) : ParseResult()
}

sealed class CerberusUiState {
    data class Disconnected(
        val lastErrorMessage: String? = null,
        val sessionLogs: List<File> = emptyList()
    ) : CerberusUiState()
    data class Scanning(val hits: List<ScanHit>) : CerberusUiState()
    data class Connecting(val device: ScanHit) : CerberusUiState()
    data class Connected(
        val device: BluetoothDevice,
        val gatt: BluetoothGatt,
        val rssi: Int,
        val lastError: String? = null,
        val dotStarMode: DotStarMode = DotStarMode.GREEN_SOLID_DIM,
        val rawPacket: String? = null,
        val status: CerberusStatus? = null,
        val showDetailsScreen: Boolean = false,
        val activeLogFile: File? = null
    ) : CerberusUiState()
    data class LogManager(val sessionLogs: List<File>) : CerberusUiState()
}

//endregion

//region --- UI Components ---

@Composable
fun CerberusImage(modifier: Modifier = Modifier) {
    AsyncImage(
        model = R.drawable.cerberuslarge2,
        contentDescription = "Cerberus",
        modifier = modifier
    )
}

enum class DotStarAnim {
    SOLID, BREATHING, FLASH, URGENT
}

enum class DotStarMode(
    val color: Color,
    val animation: DotStarAnim,
    val brightness: Float
) {
    GREEN_SOLID_DIM(Color(0xFF00C853), DotStarAnim.SOLID, 0.4f),
    GREEN_BREATHING_DIM(Color(0xFF00C853), DotStarAnim.BREATHING, 1.0f),
    RED_URGENT_BRIGHT(Color(0xFFD50000), DotStarAnim.URGENT, 1.0f)
}

@Composable
fun DotStarIndicator(mode: DotStarMode, sizeDp: Dp = 64.dp) {
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
            animationSpec = infiniteRepeatable(animation = tween(250), repeatMode = RepeatMode.Reverse),
            label = "flash"
        ).value
        DotStarAnim.URGENT -> infinite.animateFloat(
            initialValue = 0f,
            targetValue = mode.brightness,
            animationSpec = infiniteRepeatable(animation = tween(120), repeatMode = RepeatMode.Reverse),
            label = "urgent"
        ).value
    }
    Box(
        modifier = Modifier
            .size(sizeDp)
            .background(color = mode.color.copy(alpha = alpha), shape = CircleShape)
    )
}

@Composable
fun FlowAlertTriangle(sizeDp: Dp = 220.dp) {
    val infinite = rememberInfiniteTransition(label = "flowAlert")
    val alpha by infinite.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = AlertConfig.FLOW_ALERT_PERIOD_MS
                1f at 0
                1f at AlertConfig.FLOW_ALERT_ON_MS
                0f at AlertConfig.FLOW_ALERT_ON_MS + 1
                0f at AlertConfig.FLOW_ALERT_PERIOD_MS - 1
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "triangleAlpha"
    )
    Canvas(modifier = Modifier.size(sizeDp)) {
        val path = Path()
        val w = size.width
        val h = size.height
        path.moveTo(w / 2f, 0f)
        path.lineTo(w, h)
        path.lineTo(0f, h)
        path.close()
        drawPath(path = path, color = AlertConfig.FLOW_ALERT_COLOR.copy(alpha = alpha))
    }
}

@Composable
private fun StatusItem(label: String, value: String, isWarning: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = if (isWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun CerberusStatusCard(status: CerberusStatus) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Device Status", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
            StatusItem("Voltage", "${String.format(Locale.US, "%.2f", status.voltageMv / 1000f)} V", isWarning = status.isBatteryLow)
            StatusItem("Battery", if (status.isBatteryLow) "Low" else "OK", isWarning = status.isBatteryLow)
            StatusItem("Temperature", if (status.isTempOutOfRange) "Out of Range" else "OK", isWarning = status.isTempOutOfRange)
            StatusItem("Cerberus Mode", status.cerberusMode.name)
            StatusItem("Sensor Mode", status.sensorMode.name)
            StatusItem("Sensor Health", status.sensorHealth.name, isWarning = status.sensorHealth != SensorHealth.AllOK)
            StatusItem("BLE State", if (status.isBleDisconnected) "Disconnected" else "Connected", isWarning = status.isBleDisconnected)
        }
    }
}

//endregion

//region --- File Management Helper Functions ---

private fun writeToLog(file: File, text: String): Boolean {
    return try { file.appendText("$text\n"); true } catch (e: Exception) { false }
}

private fun getFilesInCache(context: Context): List<File> {
    return context.cacheDir.listFiles { _, name -> name.endsWith(".txt") }?.toList() ?: emptyList()
}

private fun copyFileToUri(context: Context, sourceFile: File, destinationUri: Uri): Boolean {
    return try {
        sourceFile.inputStream().use { inputStream ->
            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        true
    } catch (e: Exception) { false }
}

private fun deleteLogFileFromCache(file: File): Boolean {
    return try { file.delete() } catch (e: SecurityException) { false }
}

//endregion


class MainActivity : ComponentActivity() {

    private lateinit var btAdapter: BluetoothAdapter
    private var bleScanner: BluetoothLeScanner? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val btManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
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

//region --- Main Application UI & Logic ---

@Composable
fun CerberusBenchApp(
    context: Context,
    btAdapterProvider: () -> BluetoothAdapter,
    scannerProvider: () -> BluetoothLeScanner?
) {
    var uiState by remember {
        mutableStateOf<CerberusUiState>(CerberusUiState.Disconnected(sessionLogs = getFilesInCache(context)))
    }
    val logLines = remember { mutableStateListOf("App started") }
    val eventChannel = remember { Channel<BleEvent>(Channel.UNLIMITED) }
    var fileToSave by remember { mutableStateOf<File?>(null) }
    val alarmController = remember { AlarmController(context) }
    DisposableEffect(Unit) { onDispose { alarmController.release() } }

    fun log(msg: String) {
        if (logLines.size > 100) logLines.removeAt(0)
        logLines.add(msg)
    }

    val saveLogLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
        onResult = { destinationUri ->
            val sourceFile = fileToSave
            if (destinationUri != null && sourceFile != null) {
                if (copyFileToUri(context, sourceFile, destinationUri)) log("Log saved successfully.")
                else log("ERROR: Failed to save log.")
            } else {
                log("Save action cancelled.")
            }
            fileToSave = null
        }
    )

    fun deleteLogFile(file: File) {
        if (deleteLogFileFromCache(file)) {
            log("Deleted log: ${file.name}")
            val currentState = uiState
            uiState = if (currentState is CerberusUiState.LogManager)
                currentState.copy(sessionLogs = getFilesInCache(context))
            else
                CerberusUiState.Disconnected(sessionLogs = getFilesInCache(context))
        } else {
            log("ERROR: Failed to delete log: ${file.name}")
        }
    }

    val requiredPerms = remember { requiredPermissions() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results: Map<String, Boolean> ->
        val denied = results.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            log("Permissions denied: ${denied.joinToString()}")
            uiState = CerberusUiState.Disconnected(
                lastErrorMessage = "Missing permissions: ${denied.joinToString()}",
                sessionLogs = getFilesInCache(context)
            )
        } else {
            log("Permissions granted")
        }
    }

    LaunchedEffect(Unit) { permissionLauncher.launch(requiredPerms) }

    fun hasAllPerms(): Boolean = requiredPerms.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun parseStatusPacket(bytes: ByteArray): ParseResult {
        if (bytes.size != 20) return ParseResult.Error("Invalid packet size: ${bytes.size}")
        if (bytes[0] != 0x00.toByte()) return ParseResult.Error("Invalid packet header: 0x${bytes[0].toUByte().toString(16).uppercase()}")
        val calculatedCrc = crc8ccitt(bytes, 19)
        val receivedCrc = bytes[19]
        if (calculatedCrc != receivedCrc) {
            return ParseResult.Error("Invalid CRC. Expected: 0x${receivedCrc.toUByte().toString(16).uppercase()}, Calculated: 0x${calculatedCrc.toUByte().toString(16).uppercase()}")
        }
        val status0 = bytes[1].toInt() and 0xFF
        val status1 = bytes[2].toInt() and 0xFF
        return ParseResult.Success(
            CerberusStatus(
                sensorMode = SensorMode.from(status0 and 0x07),
                isBatteryLow = (status0 shr 3) and 0x01 == 1,
                isBleDisconnected = (status0 shr 4) and 0x01 == 1,
                sensorHealth = SensorHealth.from((status0 shr 5) and 0x07),
                isTempOutOfRange = status1 and 0x01 == 1,
                cerberusMode = CerberusMode.from((status1 and 0x0E) shr 1),
                voltageMv = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
            )
        )
    }

    LaunchedEffect(eventChannel) {
        val scanHits = mutableMapOf<String, ScanHit>()
        for (event in eventChannel) {
            when (event) {
                is BleEvent.ScanResultReceived -> {
                    val dev = event.result.device ?: continue
                    @SuppressLint("MissingPermission")
                    val name = if (hasAllPerms()) (dev.name ?: event.result.scanRecord?.deviceName) else event.result.scanRecord?.deviceName
                    scanHits[event.result.device.address] = ScanHit(
                        address = event.result.device.address,
                        name = name,
                        rssi = event.result.rssi,
                        lastSeenMs = System.currentTimeMillis()
                    )
                    if (uiState is CerberusUiState.Scanning) {
                        uiState = CerberusUiState.Scanning(scanHits.values.toList().sortedByDescending { it.rssi })
                    }
                }
                is BleEvent.ScanFailed -> {
                    log("Scan failed: ${event.errorCode}")
                    uiState = CerberusUiState.Disconnected(lastErrorMessage = "Scan failed with code: ${event.errorCode}", sessionLogs = getFilesInCache(context))
                }
                is BleEvent.ConnectionStateChanged -> {
                    when (event.newState) {
                        BluetoothProfile.STATE_CONNECTED -> { log("GATT connected. Discovering services…"); event.gatt.discoverServices() }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            log("GATT disconnected."); event.gatt.close()
                            alarmController.stopAlarm()
                            uiState = CerberusUiState.Disconnected(sessionLogs = getFilesInCache(context))
                        }
                    }
                }
                is BleEvent.ServicesDiscovered -> {
                    if (event.status != BluetoothGatt.GATT_SUCCESS) {
                        log("Service discovery failed: ${event.status}")
                        uiState = CerberusUiState.Disconnected(lastErrorMessage = "Service discovery failed", sessionLogs = getFilesInCache(context))
                        continue
                    }
                    log("Services discovered. Locating notify characteristic…")
                    val notifyChar = event.gatt.services.flatMap { it.characteristics }.find { it.uuid == CHAR_UUID }
                    if (notifyChar == null) {
                        log("ERROR: Notify characteristic not found: $CHAR_UUID")
                        uiState = CerberusUiState.Disconnected(lastErrorMessage = "Notify characteristic not found", sessionLogs = getFilesInCache(context))
                        continue
                    }
                    log("Found notify characteristic. Enabling notifications…")
                    event.gatt.setCharacteristicNotification(notifyChar, true)
                    val cccd = notifyChar.getDescriptor(CCCD_UUID)
                    if (cccd == null) {
                        log("ERROR: CCCD descriptor missing")
                        uiState = CerberusUiState.Disconnected(lastErrorMessage = "CCCD descriptor missing", sessionLogs = getFilesInCache(context))
                        continue
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        event.gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        event.gatt.writeDescriptor(cccd)
                    }
                    log("CCCD write initiated")
                }
                is BleEvent.DescriptorWriteComplete -> {
                    log("CCCD write complete: status=${event.status}")
                    if (event.status == BluetoothGatt.GATT_SUCCESS) {
                        log("Connection complete. Initializing automatic logging.")
                        val rssi = (uiState as? CerberusUiState.Connecting)?.device?.rssi ?: 0
                        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                        val unitId = event.gatt.device.address.replace(":", "").takeLast(6)
                        val logFile = File(context.cacheDir, "Cerberus-$unitId-$timestamp.txt")
                        val header = "Timestamp,RawPacket,SensorMode,IsBatteryLow,IsBleDisconnected,SensorHealth,IsTempOutOfRange,CerberusMode,VoltageMv"
                        if (!writeToLog(logFile, header)) log("ERROR: Failed to write log header.") else log("Logging to internal file: ${logFile.name}")
                        uiState = CerberusUiState.Connected(device = event.gatt.device, gatt = event.gatt, rssi = rssi, rawPacket = "Connection successful, waiting for data...", activeLogFile = logFile)
                    } else {
                        log("ERROR: CCCD write failed")
                        uiState = CerberusUiState.Disconnected(lastErrorMessage = "CCCD write failed", sessionLogs = getFilesInCache(context))
                    }
                }
                is BleEvent.CharacteristicChanged -> {
                    val bytes = event.value
                    val hexString = bytes.joinToString(" ") { "%02X".format(it) }
                    log("RX: $hexString")
                    val currentState = uiState
                    if (currentState !is CerberusUiState.Connected) { log("Warning: Received data while not in Connected state."); continue }
                    if (bytes.isNotEmpty() && bytes[0] == 0x00.toByte()) {
                        when (val parseResult = parseStatusPacket(bytes)) {
                            is ParseResult.Success -> {
                                val status = parseResult.status
                                var newLastError: String? = null
                                var newDotStarMode = DotStarMode.GREEN_BREATHING_DIM
                                if (status.isBatteryLow) { newLastError = "Battery Low"; newDotStarMode = DotStarMode.RED_URGENT_BRIGHT }
                                if (status.isTempOutOfRange) { newLastError = "Temperature Out of Range"; newDotStarMode = DotStarMode.RED_URGENT_BRIGHT }
                                if (status.sensorMode != SensorMode.Normal) alarmController.startAlarm()
                                else alarmController.stopAlarm()
                                currentState.activeLogFile?.let { f ->
                                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                                    if (!writeToLog(f, "$ts,$hexString,${status.sensorMode},${status.isBatteryLow},${status.isBleDisconnected},${status.sensorHealth},${status.isTempOutOfRange},${status.cerberusMode},${status.voltageMv}")) log("ERROR: Failed to write to log file.")
                                }
                                uiState = currentState.copy(rawPacket = hexString, lastError = newLastError, dotStarMode = newDotStarMode, status = status)
                            }
                            is ParseResult.Error -> {
                                currentState.activeLogFile?.let { f ->
                                    val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                                    if (!writeToLog(f, "$ts,$hexString,ERROR: ${parseResult.message},,,,,")) log("ERROR: Failed to write to log file.")
                                }
                                alarmController.stopAlarm()
                                uiState = currentState.copy(rawPacket = hexString, lastError = parseResult.message, dotStarMode = DotStarMode.RED_URGENT_BRIGHT, status = null)
                            }
                        }
                    } else {
                        currentState.activeLogFile?.let { f ->
                            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                            if (!writeToLog(f, "$ts,$hexString,,,,,,,")) log("ERROR: Failed to write to log file.")
                        }
                        uiState = currentState.copy(rawPacket = hexString, status = currentState.status, lastError = currentState.lastError)
                    }
                }
                is BleEvent.RssiRead -> {
                    if (event.status == BluetoothGatt.GATT_SUCCESS) {
                        val s = uiState
                        if (s is CerberusUiState.Connected) uiState = s.copy(rssi = event.rssi)
                    }
                }
            }
        }
    }

    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { eventChannel.trySend(BleEvent.ScanResultReceived(result)) }
            override fun onScanFailed(errorCode: Int) { eventChannel.trySend(BleEvent.ScanFailed(errorCode)) }
        }
    }

    val gattHolder = remember { mutableStateOf<BluetoothGatt?>(null) }

    @SuppressLint("MissingPermission")
    val gattCallback = remember {
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) { eventChannel.trySend(BleEvent.ConnectionStateChanged(gatt, status, newState)) }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) { eventChannel.trySend(BleEvent.ServicesDiscovered(gatt, status)) }
            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) { eventChannel.trySend(BleEvent.DescriptorWriteComplete(gatt, descriptor, status)) }
            @Deprecated("Use onCharacteristicChanged(gatt, characteristic, value) instead")
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION") val value = characteristic.value ?: return
                eventChannel.trySend(BleEvent.CharacteristicChanged(value))
            }
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) { eventChannel.trySend(BleEvent.CharacteristicChanged(value)) }
            override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) { eventChannel.trySend(BleEvent.RssiRead(rssi, status)) }
        }
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!btAdapterProvider().isEnabled) { uiState = CerberusUiState.Disconnected(lastErrorMessage = "Bluetooth is not enabled", sessionLogs = getFilesInCache(context)); return }
        if (!hasAllPerms()) { permissionLauncher.launch(requiredPerms); return }
        if (uiState is CerberusUiState.Scanning) return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scannerProvider()?.startScan(null, settings, scanCallback)
        log("Scanning started")
        uiState = CerberusUiState.Scanning(emptyList())
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scannerProvider()?.stopScan(scanCallback)
        log("Scanning stopped")
        uiState = CerberusUiState.Disconnected(sessionLogs = getFilesInCache(context))
    }

    @SuppressLint("MissingPermission")
    fun connectTo(hit: ScanHit) {
        val dev = btAdapterProvider().getRemoteDevice(hit.address)
        stopScan()
        log("Connecting to ${hit.address}…")
        uiState = CerberusUiState.Connecting(hit)
        val gatt = dev.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        gattHolder.value = gatt
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        log("Disconnecting…")
        gattHolder.value?.disconnect()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val state = uiState) {
            is CerberusUiState.Disconnected -> DisconnectedScreen(state = state, onStartScan = { startScan() }, onShowLogs = { uiState = CerberusUiState.LogManager(state.sessionLogs) })
            is CerberusUiState.Scanning -> ScanningScreen(state = state, onStopScan = { stopScan() }, onConnectToDevice = { hit -> connectTo(hit) })
            is CerberusUiState.Connecting -> ConnectingScreen(state)
            is CerberusUiState.Connected -> {
                if (state.showDetailsScreen)
                    DisplayScreen(state = state, onReturn = { uiState = (uiState as CerberusUiState.Connected).copy(showDetailsScreen = false) })
                else
                    DashboardScreen(
                        state = state,
                        isAlarmSnoozed = alarmController.isSnoozed,
                        onDisconnect = { disconnect() },
                        onShowDetails = { uiState = (uiState as CerberusUiState.Connected).copy(showDetailsScreen = true) },
                        onSilenceAlarm = { alarmController.snooze() }
                    )
            }
            is CerberusUiState.LogManager -> LogManagerScreen(
                state = state,
                onSaveLog = { logFile -> fileToSave = logFile; saveLogLauncher.launch(logFile.name) },
                onDeleteLog = { logFile -> deleteLogFile(logFile) },
                onBack = { uiState = CerberusUiState.Disconnected(sessionLogs = getFilesInCache(context)) }
            )
        }
    }
}

//endregion

//region --- Screen Composables ---

@Composable
fun DisconnectedScreen(state: CerberusUiState.Disconnected, onStartScan: () -> Unit, onShowLogs: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CerberusImage(modifier = Modifier.height(150.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onStartScan) { Text("Scan for Cerberus Devices") }
        state.lastErrorMessage?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        if (state.sessionLogs.isNotEmpty()) {
            Spacer(modifier = Modifier.height(64.dp))
            OutlinedButton(onClick = onShowLogs) { Text("Session Logs") }
        }
    }
}

@Composable
fun LogManagerScreen(state: CerberusUiState.LogManager, onSaveLog: (File) -> Unit, onDeleteLog: (File) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Session Logs", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        if (state.sessionLogs.isEmpty()) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("No saved logs found.", style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.sessionLogs.sortedByDescending { it.lastModified() }) { logFile ->
                    Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(2.dp)) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = logFile.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row {
                                IconButton(onClick = { onSaveLog(logFile) }) { Icon(Icons.Outlined.Share, contentDescription = "Save Log") }
                                IconButton(onClick = { onDeleteLog(logFile) }) { Icon(Icons.Outlined.Delete, contentDescription = "Delete Log", tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = onBack) { Text("Return") }
    }
}

@Composable
fun ScanningScreen(state: CerberusUiState.Scanning, onStopScan: () -> Unit, onConnectToDevice: (ScanHit) -> Unit) {
    val cerberusHits = state.hits.filter { it.name?.contains(TARGET_NAME_SUBSTR, ignoreCase = true) == true }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Scanning...", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(16.dp))
            CircularProgressIndicator()
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onStopScan) { Text("Stop Scan") }
        Spacer(modifier = Modifier.height(16.dp))
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            if (cerberusHits.isNotEmpty()) {
                item { Text("Cerberus Devices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 8.dp)) }
                items(cerberusHits) { hit ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onConnectToDevice(hit) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(modifier = Modifier.weight(1f)) { Text(hit.name ?: "Unknown", style = MaterialTheme.typography.bodyLarge); Text(hit.address, style = MaterialTheme.typography.bodySmall) }
                            Text("${hit.rssi} dBm")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectingScreen(state: CerberusUiState.Connecting) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text("Connecting to ${state.device.name ?: state.device.address}...")
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DashboardScreen(
    state: CerberusUiState.Connected,
    isAlarmSnoozed: Boolean,
    onDisconnect: () -> Unit,
    onShowDetails: () -> Unit,
    onSilenceAlarm: () -> Unit
) {
    LaunchedEffect(state.device.address) {
        while (true) { state.gatt.readRemoteRssi(); delay(3000) }
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(text = "Cerberus", style = MaterialTheme.typography.headlineSmall)
                Text(text = "Unit ID: ${state.device.address.replace(":", "").takeLast(6)}", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                state.status?.let {
                    Text(text = "Battery: ${if (it.isBatteryLow) "Low" else "OK"}", style = MaterialTheme.typography.bodyMedium, color = if (it.isBatteryLow) MaterialTheme.colorScheme.error else Color.Gray)
                    Icon(imageVector = Icons.Default.Bluetooth, contentDescription = "Bluetooth Connected", tint = Color.Blue, modifier = Modifier.size(36.dp))
                }
            }
            IconButton(onClick = onShowDetails) { Icon(Icons.Outlined.Info, contentDescription = "Show Details", modifier = Modifier.size(48.dp)) }
        }
        Spacer(modifier = Modifier.height(60.dp))
        val status = state.status
        val isFlowAlert = status != null && status.sensorMode != SensorMode.Normal
        when {
            status == null -> {
                Text(text = "Initializing", style = MaterialTheme.typography.headlineMedium, color = Color.Gray)
            }
            isFlowAlert -> {
                FlowAlertTriangle(sizeDp = 220.dp)
            }
            else -> {
                DotStarIndicator(mode = state.dotStarMode, sizeDp = 220.dp)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        status?.let {
            Text(
                text = it.sensorMode.name,
                style = MaterialTheme.typography.titleLarge,
                color = if (it.sensorMode != SensorMode.Normal) AlertConfig.FLOW_ALERT_COLOR else Color.Unspecified
            )
        }
        if (isFlowAlert) {
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onSilenceAlarm,
                enabled = !isAlarmSnoozed,
                colors = ButtonDefaults.buttonColors(containerColor = AlertConfig.FLOW_ALERT_COLOR)
            ) {
                Text(if (isAlarmSnoozed) "Alarm Silenced (auto-resumes)" else "Silence Alarm")
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onDisconnect) { Text("Stop & Disconnect") }
    }
}

@Composable
fun DisplayScreen(state: CerberusUiState.Connected, onReturn: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Cerberus Details", style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(16.dp))
        StatusItem("Signal Strength:", "${state.rssi} dBm")
        state.activeLogFile?.let { StatusItem("Logging To: ", it.name) }
        state.rawPacket?.let { Spacer(modifier = Modifier.height(16.dp)); Text(text = "Raw Packet: $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp)) }
        state.status?.let { Spacer(modifier = Modifier.height(16.dp)); CerberusStatusCard(status = it) }
        Spacer(modifier = Modifier.weight(1f))
        state.lastError?.let { Text(text = "Last Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 16.dp)) }
        OutlinedButton(onClick = onReturn) { Text("Return") }
    }
}

//endregion

//region --- Helper Functions ---

private fun crc8ccitt(data: ByteArray, length: Int): Byte {
    var crc = 0x00
    for (i in 0 until length) {
        crc = crc xor (data[i].toInt() and 0xFF)
        for (j in 0..7) { crc = if ((crc and 0x80) != 0) (crc shl 1) xor 0x07 else crc shl 1 }
    }
    return (crc and 0xFF).toByte()
}

fun requiredPermissions(): Array<String> {
    val permissions = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        permissions.add(Manifest.permission.BLUETOOTH)
        permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
    }
    permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
    return permissions.toTypedArray()
}

//endregion
