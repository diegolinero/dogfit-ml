package com.astralimit.dogfit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.UUID
import android.os.ParcelUuid

class DogFitBleService : Service() {

    companion object {
        private const val TAG = "DogFitBleService"

        private const val BLE_ACTION_NEW_DATA = "com.astralimit.dogfit.NEW_DATA"
        private const val BLE_ACTION_STATUS = "com.astralimit.dogfit.BLE_STATUS"
        private const val BLE_EXTRA_CONNECTED = "connected"
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null

    // Firmware UUIDs
    private val SERVICE_UUID = UUID.fromString("0000ABCD-0000-1000-8000-00805F9B34FB")
    private val RESULT_CHAR_UUID = UUID.fromString("0000ABCF-0000-1000-8000-00805F9B34FB")
    private val ACK_CHAR_UUID    = UUID.fromString("0000ABD0-0000-1000-8000-00805F9B34FB")
    private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var resultChar: BluetoothGattCharacteristic? = null
    private var ackChar: BluetoothGattCharacteristic? = null
    private var notificationsEnabled = false
    private var connectInProgress = false

    private var bleEstimatedStepsTotal = 0

    // Record = 11 bytes
    private val REC_BYTES = 11

    // Reassembly buffer
    private val rxBuffer = ByteArray(8192)
    private var rxLen = 0

    // ACK state (uint32)
    private var lastSeqProcessed: Long = -1L
    private var lastAckSent: Long = -1L
    private var lastAckSentAtMs: Long = 0
    private val ackMinIntervalMs = 250L

    // Scan state
    private var scanning = false
    private val scanTimeoutMs = 15_000L

    // Connection timeout
    private val connectTimeoutMs = 12_000L

    private val handler = Handler(Looper.getMainLooper())

    private val stopScanRunnable = Runnable {
        stopScanning()
        handler.postDelayed({ startScanning() }, 1000)
    }

    private val connectTimeoutRunnable = Runnable {
        if (connectInProgress) {
            Log.e(TAG, "Timeout de conexión (${connectTimeoutMs}ms). Limpiando y reintentando scan.")
            broadcastStatus(false)
            cleanupGatt("connectTimeout")
            handler.postDelayed({ startScanning() }, 800)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, "DogFitChannel")
            .setContentTitle("Collar DogFit")
            .setContentText("Buscando conexión...")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()

        startForeground(1, notification)
        startScanning()
        return START_STICKY
    }

    // =====================================================
    // Broadcast helpers (más robusto)
    // =====================================================
    private fun broadcastStatus(connected: Boolean) {
        val i = Intent(BLE_ACTION_STATUS).apply {
            setPackage(packageName) // limita a tu app
            putExtra(BLE_EXTRA_CONNECTED, connected)
        }
        sendBroadcast(i)
    }

    private fun broadcastNewData(intent: Intent) {
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    // =====================================================
    // SCAN (con filtro por UUID para NO llenar log)
    // =====================================================
    private fun startScanning() {
        if (scanning) return
        if (connectInProgress || bluetoothGatt != null) {
            // Si estamos conectando/conectados, no escanear
            return
        }

        if (!hasBlePermissions()) {
            Log.e(TAG, "Permisos BLE faltantes antes de escanear/conectar")
            handler.postDelayed({ startScanning() }, 2000)
            return
        }

        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            Log.e(TAG, "Bluetooth apagado en startScanning; reintentando")
            handler.postDelayed({ startScanning() }, 2000)
            return
        }

        val scanner = adapter.bluetoothLeScanner ?: return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // ✅ Filtro por SERVICE_UUID
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        rxLen = 0

        Log.d(TAG, "Iniciando escaneo (con filtro UUID)...")
        scanning = true
        handler.removeCallbacks(stopScanRunnable)
        handler.postDelayed(stopScanRunnable, scanTimeoutMs)

        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
            Log.i(TAG, "startScan ok (timeout=${scanTimeoutMs}ms)")
        } catch (se: SecurityException) {
            Log.e(TAG, "startScan SecurityException (permisos)", se)
            scanning = false
            handler.postDelayed({ startScanning() }, 2000)
        } catch (t: Throwable) {
            Log.e(TAG, "startScan error inesperado", t)
            scanning = false
            handler.postDelayed({ startScanning() }, 2000)
        }
    }

    private fun stopScanning() {
        if (!scanning) return
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Throwable) {}
        scanning = false
        handler.removeCallbacks(stopScanRunnable)
        Log.d(TAG, "Scan detenido")
    }

    private fun isDogfitCandidate(result: ScanResult): Boolean {
        val name = result.device.name ?: result.scanRecord?.deviceName ?: ""
        // si tu firmware anuncia con "DOGFIT" esto ayuda cuando el UUID no viene en scanRecord
        return name.contains("DOGFIT", ignoreCase = true) || (result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isDogfitCandidate(result)) return

            val name = result.device.name ?: result.scanRecord?.deviceName ?: "N/A"
            val serviceUuids = result.scanRecord?.serviceUuids?.joinToString { it.uuid.toString() } ?: "none"

            Log.i(TAG, "DOGFIT candidato: name=$name addr=${result.device.address} rssi=${result.rssi} uuids=$serviceUuids")

            if (connectInProgress || bluetoothGatt != null) {
                Log.d(TAG, "Ignorando DOGFIT porque ya hay conexión en curso/activa (connectInProgress=$connectInProgress gatt=${bluetoothGatt != null})")
                return
            }

            stopScanning()
            connectInProgress = true
            handler.removeCallbacks(connectTimeoutRunnable)
            handler.postDelayed(connectTimeoutRunnable, connectTimeoutMs)

            Log.i(TAG, "connectGatt llamado (TRANSPORT_LE)")
            try {
                bluetoothGatt = result.device.connectGatt(
                    this@DogFitBleService,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            } catch (se: SecurityException) {
                Log.e(TAG, "connectGatt SecurityException", se)
                connectInProgress = false
                handler.removeCallbacks(connectTimeoutRunnable)
                cleanupGatt("connectGattSecurityException")
                handler.postDelayed({ startScanning() }, 1000)
            } catch (t: Throwable) {
                Log.e(TAG, "connectGatt error inesperado", t)
                connectInProgress = false
                handler.removeCallbacks(connectTimeoutRunnable)
                cleanupGatt("connectGattThrowable")
                handler.postDelayed({ startScanning() }, 1000)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed: $errorCode")
            scanning = false
            handler.removeCallbacks(stopScanRunnable)
            handler.postDelayed({ startScanning() }, 2000)
        }
    }

    // =====================================================
    // GATT
    // =====================================================
    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "onConnectionStateChange status=$status newState=$newState")

            // ✅ Si status != GATT_SUCCESS, tratar como fallo
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "GATT error status=$status, forzando cleanup + rescan")
                connectInProgress = false
                handler.removeCallbacks(connectTimeoutRunnable)
                broadcastStatus(false)
                cleanupGatt("statusNotSuccess:$status")
                handler.postDelayed({ startScanning() }, 1200)
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT Conectado. Descubriendo servicios...")
                connectInProgress = false
                handler.removeCallbacks(connectTimeoutRunnable)

                // asegurar que el campo apunte a este gatt
                bluetoothGatt = gatt

                rxLen = 0
                lastSeqProcessed = -1L
                lastAckSent = -1L
                lastAckSentAtMs = 0
                bleEstimatedStepsTotal = 0
                notificationsEnabled = false
                resultChar = null
                ackChar = null

                broadcastStatus(true)

                try { gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) } catch (_: Throwable) {}
                gatt.discoverServices()

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.e(TAG, "GATT Desconectado. Reintentando scan...")
                connectInProgress = false
                handler.removeCallbacks(connectTimeoutRunnable)

                broadcastStatus(false)
                cleanupGatt("disconnected")

                handler.postDelayed({ startScanning() }, 1500)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "Servicios descubiertos. status=$status. Solicitando MTU...")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "discoverServices status no exitoso: $status")
                setupCharsAndEnableNotifications(gatt, "servicesDiscovered-nonSuccess")
                return
            }
            val mtuRequested = try { gatt.requestMtu(256) } catch (_: Throwable) { false }
            if (!mtuRequested) {
                Log.w(TAG, "requestMtu(256) falló, usando fallback")
                setupCharsAndEnableNotifications(gatt, "requestMtu-fallback")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU listo: $mtu. status=$status. Configurando...")
            setupCharsAndEnableNotifications(gatt, "onMtuChanged")
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != RESULT_CHAR_UUID) return
            onResultNotify(characteristic.value ?: ByteArray(0))
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != RESULT_CHAR_UUID) return
            onResultNotify(value)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.characteristic?.uuid == RESULT_CHAR_UUID) {
                Log.i(TAG, "CCCD write status=$status")
                notificationsEnabled = status == BluetoothGatt.GATT_SUCCESS
                if (notificationsEnabled) {
                    maybeSendAck(force = true)
                }
            }
        }
    }

    private fun cleanupGatt(reason: String) {
        Log.w(TAG, "cleanupGatt reason=$reason (close + null)")
        try { bluetoothGatt?.disconnect() } catch (_: Throwable) {}
        try { bluetoothGatt?.close() } catch (_: Throwable) {}
        bluetoothGatt = null

        resultChar = null
        ackChar = null
        notificationsEnabled = false
        rxLen = 0
    }

    private fun hasBlePermissions(): Boolean {
        val hasScan = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else true
        val hasConnect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
        val hasFine = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        } else true

        if (!hasScan || !hasConnect || !hasFine) {
            Log.w(TAG, "Permisos faltantes: scan=$hasScan connect=$hasConnect fineLocation=$hasFine")
        }
        return hasScan && hasConnect && hasFine
    }

    private fun setupCharsAndEnableNotifications(gatt: BluetoothGatt, source: String) {
        val service = gatt.getService(SERVICE_UUID)
        Log.i(TAG, "setupChars source=$source serviceNull=${service == null}")
        if (service == null) {
            Log.e(TAG, "Servicio ABCD no encontrado. (¿firmware correcto?)")
            return
        }

        resultChar = service.getCharacteristic(RESULT_CHAR_UUID)
        ackChar = service.getCharacteristic(ACK_CHAR_UUID)

        Log.i(TAG, "resultChar null? ${resultChar == null} / ackChar null? ${ackChar == null}")

        if (resultChar == null) {
            Log.e(TAG, "Característica ABCF no encontrada")
            return
        }

        if (ackChar == null) {
            Log.e(TAG, "Característica ABD0 (ACK) no encontrada (sin ACK no hay reliability)")
        } else {
            ackChar?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        enableNotifications(gatt, resultChar)
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?) {
        if (characteristic == null) return
        val setOk = gatt.setCharacteristicNotification(characteristic, true)
        Log.i(TAG, "setCharacteristicNotification ok=$setOk")

        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val writeOk = gatt.writeDescriptor(descriptor)
            Log.i(TAG, "write CCCD launched=$writeOk")
        } else {
            Log.e(TAG, "Descriptor CCCD 0x2902 no encontrado")
            notificationsEnabled = true
        }
    }

    // =====================================================
    // NOTIFY parse 10B/record + ACK uint32
    // =====================================================
    private fun onResultNotify(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        Log.d(TAG, "onCharacteristicChanged bytes=${chunk.size}")

        if (rxLen + chunk.size > rxBuffer.size) {
            Log.w(TAG, "RX overflow. Reset buffer.")
            rxLen = 0
            return
        }
        System.arraycopy(chunk, 0, rxBuffer, rxLen, chunk.size)
        rxLen += chunk.size

        var offset = 0
        var processedAny = false

        while (rxLen - offset >= REC_BYTES) {
            val tMs = readUInt32LE(rxBuffer, offset + 0)
            val label = rxBuffer[offset + 4].toInt() and 0xFF
            val conf = rxBuffer[offset + 5].toInt() and 0xFF
            val bat = rxBuffer[offset + 6].toInt() and 0xFF
            val seq = readUInt32LE(rxBuffer, offset + 7)

            lastSeqProcessed = seq
            processedAny = true

            val inc = estimateStepsIncrement(label, conf)
            bleEstimatedStepsTotal += inc

            val intent = Intent(BLE_ACTION_NEW_DATA).apply {
                putExtra("activity_label", label)
                putExtra("confidence", conf)
                putExtra("battery_percent", bat)
                putExtra("sequence", seq)
                putExtra("sensor_time_ms", tMs)
                putExtra("steps_total", bleEstimatedStepsTotal)

                putExtra(
                    "data",
                    JSONObject().apply {
                        put("act", label)
                        put("stp", bleEstimatedStepsTotal)
                        put("conf", conf)
                        put("seq", seq)
                        put("t_ms", tMs)
                    }.toString()
                )
            }
            broadcastNewData(intent)

            offset += REC_BYTES
        }

        if (offset > 0) {
            val remaining = rxLen - offset
            if (remaining > 0) System.arraycopy(rxBuffer, offset, rxBuffer, 0, remaining)
            rxLen = remaining
        }

        if (processedAny) maybeSendAck(force = false)
    }

    private fun maybeSendAck(force: Boolean) {
        val gatt = bluetoothGatt ?: return
        val c = ackChar ?: return
        if (!notificationsEnabled) return
        if (lastSeqProcessed < 0) return

        val now = SystemClock.elapsedRealtime()
        val shouldSend = force || (
                lastSeqProcessed != lastAckSent &&
                        (now - lastAckSentAtMs) >= ackMinIntervalMs
                )
        if (!shouldSend) return

        val ack = ByteArray(4)
        writeUInt32LE(ack, 0, lastSeqProcessed)

        c.value = ack
        val ok = try { gatt.writeCharacteristic(c) } catch (_: Throwable) { false }
        Log.i(TAG, "ACK enviado seq=$lastSeqProcessed writeOk=$ok")
        if (ok) {
            lastAckSent = lastSeqProcessed
            lastAckSentAtMs = now
        } else {
            Log.w(TAG, "ACK writeCharacteristic failed")
        }
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
        val b0 = bytes[offset + 0].toLong() and 0xFF
        val b1 = (bytes[offset + 1].toLong() and 0xFF) shl 8
        val b2 = (bytes[offset + 2].toLong() and 0xFF) shl 16
        val b3 = (bytes[offset + 3].toLong() and 0xFF) shl 24
        return (b0 or b1 or b2 or b3) and 0xFFFFFFFFL
    }

    private fun writeUInt32LE(dst: ByteArray, offset: Int, value: Long) {
        val v = value and 0xFFFFFFFFL
        dst[offset + 0] = (v and 0xFF).toByte()
        dst[offset + 1] = ((v shr 8) and 0xFF).toByte()
        dst[offset + 2] = ((v shr 16) and 0xFF).toByte()
        dst[offset + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun estimateStepsIncrement(label: Int, confidence: Int): Int {
        val base = when (label) {
            0 -> 0
            1 -> 4
            2 -> 8
            3 -> 6
            else -> 1
        }
        val scaled = (base * (confidence / 100f)).toInt()
        return scaled.coerceAtLeast(if (base == 0) 0 else 1)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "DogFitChannel",
                "DogFit",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?) = null
}
