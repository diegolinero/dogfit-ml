package com.astralimit.dogfit

import android.app.*
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.*

class DogFitBleService : Service() {

    companion object {
        private const val BLE_ACTION_NEW_DATA = "com.astralimit.dogfit.NEW_DATA"
        private const val BLE_ACTION_STATUS = "com.astralimit.dogfit.BLE_STATUS"
        private const val BLE_EXTRA_CONNECTED = "connected"
    }

    private val TAG = "DogFitBleService"
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private val SERVICE_UUID = UUID.fromString("0000ABCD-0000-1000-8000-00805F9B34FB")
    private val RESULT_CHAR_UUID = UUID.fromString("0000ABCF-0000-1000-8000-00805F9B34FB")
    private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bleEstimatedStepsTotal = 0

    // ==========================
    // RX reassembly buffer (óptimo)
    // ==========================
    private val rxBuffer = ByteArray(4096)
    private var rxLen = 0

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

    private fun startScanning() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        Log.d(TAG, "Iniciando escaneo...")
        scanner.startScan(listOf(filter), settings, scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: result.scanRecord?.deviceName ?: "N/A"
            Log.d(TAG, "Dispositivo encontrado: $deviceName")
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(this)
            bluetoothGatt = result.device.connectGatt(this@DogFitBleService, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "GATT Conectado. Descubriendo servicios...")

                // Recomendado: mejor throughput/latencia
                try {
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                } catch (_: Throwable) {}

                // Reset del buffer al reconectar
                rxLen = 0

                sendBroadcast(Intent(BLE_ACTION_STATUS).apply {
                    putExtra(BLE_EXTRA_CONNECTED, true)
                })
                gatt.discoverServices()

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.e(TAG, "GATT Desconectado. Reintentando...")

                sendBroadcast(Intent(BLE_ACTION_STATUS).apply {
                    putExtra(BLE_EXTRA_CONNECTED, false)
                })
                try { gatt.close() } catch (_: Throwable) {}

                // Reset buffer
                rxLen = 0

                Handler(Looper.getMainLooper()).postDelayed({ startScanning() }, 5000)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.i(TAG, "Servicios OK. Solicitando MTU...")
            gatt.requestMtu(256)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU listo: $mtu. Activando Notificaciones...")
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "Servicio BLE no encontrado")
                return
            }
            enableNotifications(gatt, service.getCharacteristic(RESULT_CHAR_UUID))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.characteristic?.uuid == RESULT_CHAR_UUID) {
                Log.i(TAG, "CCCD write status=$status para RESULT_CHAR_UUID")
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != RESULT_CHAR_UUID) return
            dispatchFirmwareChunk(value)
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic?) {
        if (characteristic == null) {
            Log.e(TAG, "Característica BLE no encontrada")
            return
        }
        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)
        gatt.setCharacteristicNotification(characteristic, true)
        if (descriptor != null) {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(descriptor)
        } else {
            Log.e(TAG, "Descriptor BLE no encontrado")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("DogFitChannel", "DogFit", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?) = null

    // =========================================================
    //  ÓPTIMO: rearmar bytes como stream + consumir frames de 8
    // =========================================================
    private fun dispatchFirmwareChunk(chunk: ByteArray) {
        if (chunk.isEmpty()) return

        // 1) Append al buffer
        if (rxLen + chunk.size > rxBuffer.size) {
            Log.w(TAG, "RX buffer overflow. Reset.")
            rxLen = 0
            return
        }
        System.arraycopy(chunk, 0, rxBuffer, rxLen, chunk.size)
        rxLen += chunk.size

        // 2) Consumir registros completos (8 bytes c/u)
        var offset = 0
        while (rxLen - offset >= 8) {
            val millis = readUInt32LE(rxBuffer, offset)
            val label = rxBuffer[offset + 4].toInt() and 0xFF
            val confidence = rxBuffer[offset + 5].toInt() and 0xFF
            val sequence = readUInt16LE(rxBuffer, offset + 6)

            val estimatedIncrement = estimateStepsIncrement(label, confidence)
            bleEstimatedStepsTotal += estimatedIncrement

            val intent = Intent(BLE_ACTION_NEW_DATA).apply {
                putExtra("activity_label", label)
                putExtra("confidence", confidence)
                putExtra("sequence", sequence)
                putExtra("sensor_time_ms", millis)
                putExtra("steps_total", bleEstimatedStepsTotal)

                putExtra("data", JSONObject().apply {
                    put("act", label)
                    put("stp", bleEstimatedStepsTotal)
                    put("conf", confidence)
                    put("seq", sequence)
                    put("t_ms", millis)
                }.toString())
            }
            sendBroadcast(intent)

            offset += 8
        }

        // 3) Compactar sobrantes (<8)
        if (offset > 0) {
            val remaining = rxLen - offset
            if (remaining > 0) {
                System.arraycopy(rxBuffer, offset, rxBuffer, 0, remaining)
            }
            rxLen = remaining
        }
    }

    private fun readUInt16LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun readUInt32LE(bytes: ByteArray, offset: Int): Long {
        val b0 = bytes[offset].toLong() and 0xFF
        val b1 = (bytes[offset + 1].toLong() and 0xFF) shl 8
        val b2 = (bytes[offset + 2].toLong() and 0xFF) shl 16
        val b3 = (bytes[offset + 3].toLong() and 0xFF) shl 24
        return b0 or b1 or b2 or b3
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
}