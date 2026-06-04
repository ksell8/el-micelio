package com.disturb.elmicelio

import android.Manifest
import android.content.Context
import android.os.ParcelUuid
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.nordicsemi.android.support.v18.scanner.BluetoothLeScannerCompat
import no.nordicsemi.android.support.v18.scanner.ScanCallback
import no.nordicsemi.android.support.v18.scanner.ScanFilter
import no.nordicsemi.android.support.v18.scanner.ScanResult
import no.nordicsemi.android.support.v18.scanner.ScanSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

enum class BeaconMode { IBEACON, EDDYSTONE_UID }

// ─── Data model ──────────────────────────────────────────────────────────────

data class SemillaNode(
    val id: String,         // "major-minor"
    val major: Int,
    val minor: Int,
    val rssi: Int,
    val proximity: Proximity,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val displayName: String get() = "Semilla-${minor.toString(16).padStart(4, '0').uppercase()}"

    enum class Proximity(val label: String) {
        IMMEDIATE("Immediate (~< 1m)"),
        NEAR("Near (~1–3m)"),
        FAR("Far (> 3m)"),
        UNKNOWN("Unknown")
    }
}

fun rssiToProximity(rssi: Int): SemillaNode.Proximity = when {
    rssi >= -60 -> SemillaNode.Proximity.IMMEDIATE
    rssi >= -75 -> SemillaNode.Proximity.NEAR
    rssi >= -90 -> SemillaNode.Proximity.FAR
    else        -> SemillaNode.Proximity.UNKNOWN
}

// ─── Scanner ─────────────────────────────────────────────────────────────────

class BeaconScanner(private val context: Context) {

    companion object {
        val BEACON_MODE = BeaconMode.EDDYSTONE_UID

        // iBeacon
        val SEMILLA_UUID: UUID = UUID.fromString("64697374-7572-4274-a865-737461747573")

        // Eddystone-UID
        val EDDYSTONE_SERVICE_UUID: ParcelUuid = ParcelUuid.fromString("0000feaa-0000-1000-8000-00805f9b34fb")
        val EDDYSTONE_NAMESPACE: ByteArray = byteArrayOf(
            0x64, 0x69, 0x73, 0x74, 0x75, 0x72, 0x42, 0x74, 0xA8.toByte(), 0x65
        )
        val EDDYSTONE_INSTANCE: ByteArray = byteArrayOf(
            0x73, 0x74, 0x61, 0x74, 0x75, 0x73
        )

        private const val NODE_TIMEOUT_MS = 10_000L
    }

    private val _nodes = MutableStateFlow<List<SemillaNode>>(emptyList())
    val nodes: StateFlow<List<SemillaNode>> = _nodes.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val nodeMap = mutableMapOf<String, SemillaNode>()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            parse(result)?.let { upsertNode(it) }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { result -> parse(result)?.let { upsertNode(it) } }
        }

        override fun onScanFailed(errorCode: Int) {
            _error.value = "BLE scan failed (code $errorCode)"
            _isScanning.value = false
        }
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    @RequiresPermission(allOf = [
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.ACCESS_FINE_LOCATION
    ])
    fun startScanning() {
        val scanner = BluetoothLeScannerCompat.getScanner()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setUseHardwareBatchingIfSupported(false)
            .build()

        val filters: List<ScanFilter>? = when (BEACON_MODE) {
            BeaconMode.IBEACON -> listOf(ScanFilter.Builder().setManufacturerData(0x004C, null).build())
            BeaconMode.EDDYSTONE_UID -> null
        }
        scanner.startScan(filters, settings, scanCallback)
        _isScanning.value = true
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScanning() {
        BluetoothLeScannerCompat.getScanner().stopScan(scanCallback)
        _isScanning.value = false
        nodeMap.clear()
        _nodes.value = emptyList()
    }

    fun pruneStaleNodes() {
        val cutoff = System.currentTimeMillis() - NODE_TIMEOUT_MS
        nodeMap.entries.removeAll { it.value.lastSeen < cutoff }
        publishNodes()
    }

    fun clearError() { _error.value = null }

    // ─── Private ─────────────────────────────────────────────────────────────

    private fun parse(result: ScanResult): SemillaNode? = when (BEACON_MODE) {
        BeaconMode.IBEACON       -> parseIBeacon(result)
        BeaconMode.EDDYSTONE_UID -> parseEddystoneUid(result)
    }

    private fun parseEddystoneUid(result: ScanResult): SemillaNode? {
        val serviceData = result.scanRecord?.getServiceData(EDDYSTONE_SERVICE_UUID) ?: return null
        return parseEddystoneUidPayload(serviceData, result.rssi)
    }

    private fun parseIBeacon(result: ScanResult): SemillaNode? {
        val manufacturerData = result.scanRecord?.getManufacturerSpecificData(0x004C) ?: return null
        return parseIBeaconPayload(manufacturerData, result.rssi)
    }

    private fun upsertNode(node: SemillaNode) {
        nodeMap[node.id] = node
        publishNodes()
    }

    private fun publishNodes() {
        _nodes.value = nodeMap.values
            .sortedBy { it.proximity.ordinal }
    }
}

/**
 * Pure iBeacon payload parser — extracted for unit testability.
 *
 * iBeacon manufacturer data layout (after 2-byte company ID 0x004C):
 *   Byte 0:     type   = 0x02
 *   Byte 1:     length = 0x15
 *   Bytes 2–17: UUID (16 bytes, big-endian)
 *   Bytes 18–19: Major (big-endian unsigned)
 *   Bytes 20–21: Minor (big-endian unsigned)
 *   Byte 22:    TX power
 */
internal fun parseIBeaconPayload(manufacturerData: ByteArray, rssi: Int): SemillaNode? {
    if (manufacturerData.size < 23) return null
    if (manufacturerData[0] != 0x02.toByte()) return null
    if (manufacturerData[1] != 0x15.toByte()) return null

    val uuidBytes = manufacturerData.copyOfRange(2, 18)
    val buffer = ByteBuffer.wrap(uuidBytes).order(ByteOrder.BIG_ENDIAN)
    val uuid = UUID(buffer.getLong(), buffer.getLong())

    if (uuid != BeaconScanner.SEMILLA_UUID) return null

    val major = ((manufacturerData[18].toInt() and 0xFF) shl 8) or
                 (manufacturerData[19].toInt() and 0xFF)
    val minor = ((manufacturerData[20].toInt() and 0xFF) shl 8) or
                 (manufacturerData[21].toInt() and 0xFF)

    return SemillaNode(
        id = "$major-$minor",
        major = major,
        minor = minor,
        rssi = rssi,
        proximity = rssiToProximity(rssi)
    )
}

/**
 * Eddystone-UID payload layout (service data for UUID 0xFEAA):
 *   Byte 0:      frame type = 0x00
 *   Byte 1:      TX power
 *   Bytes 2–11:  Namespace (10 bytes)
 *   Bytes 12–17: Instance (6 bytes)
 *   Bytes 18–19: Reserved (optional)
 */
internal fun parseEddystoneUidPayload(serviceData: ByteArray, rssi: Int): SemillaNode? {
    if (serviceData.size < 18) return null
    if (serviceData[0] != 0x00.toByte()) return null

    val namespace = serviceData.copyOfRange(2, 12)
    val instance  = serviceData.copyOfRange(12, 18)

    if (!namespace.contentEquals(BeaconScanner.EDDYSTONE_NAMESPACE)) return null

    val instanceHex = instance.joinToString("") { "%02X".format(it) }
    val minor = ((instance[4].toInt() and 0xFF) shl 8) or (instance[5].toInt() and 0xFF)

    return SemillaNode(
        id = instanceHex,
        major = 0,
        minor = minor,
        rssi = rssi,
        proximity = rssiToProximity(rssi)
    )
}

/** Builds a well-formed iBeacon manufacturer-data payload for a given UUID, major, and minor. */
internal fun buildIBeaconPayload(uuid: UUID, major: Int, minor: Int, txPower: Byte = -59): ByteArray {
    val bytes = ByteArray(23)
    bytes[0] = 0x02
    bytes[1] = 0x15
    val bb = ByteBuffer.wrap(bytes, 2, 16).order(ByteOrder.BIG_ENDIAN)
    bb.putLong(uuid.mostSignificantBits)
    bb.putLong(uuid.leastSignificantBits)
    bytes[18] = (major ushr 8).toByte()
    bytes[19] = (major and 0xFF).toByte()
    bytes[20] = (minor ushr 8).toByte()
    bytes[21] = (minor and 0xFF).toByte()
    bytes[22] = txPower
    return bytes
}
