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
    private val TAG = "DogFitBleService"
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private val SERVICE_UUID = UUID.fromString("0000ABCD-0000-1000-8000-00805F9B34FB")
    private val RESULT_CHAR_UUID = UUID.fromString("0000ABCF-0000-1000-8000-00805F9B34FB")
    private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var estimatedStepsTotal = 0

    private var estimatedStepsTotal = 0
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
                sendBleStatusBroadcast(true)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.e(TAG, "GATT Desconectado. Reintentando...")
                sendBleStatusBroadcast(false)
                gatt.close()
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

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != RESULT_CHAR_UUID) return
            dispatchFirmwareBatch(characteristic.value ?: return)
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

    private fun dispatchFirmwareBatch(payload: ByteArray) {
        if (payload.isEmpty() || payload.size % 8 != 0) {
            Log.w(TAG, "Payload inválido recibido (${payload.size} bytes)")
            return
        }

        for (offset in payload.indices step 8) {
            val millis = readUInt32LE(payload, offset)
            val label = payload[offset + 4].toInt() and 0xFF
            val confidence = payload[offset + 5].toInt() and 0xFF
            val sequence = readUInt16LE(payload, offset + 6)

            val estimatedIncrement = estimateStepsIncrement(label, confidence)
            estimatedStepsTotal += estimatedIncrement

            val intent = Intent("com.astralimit.dogfit.NEW_DATA").apply {
                putExtra("activity_label", label)
                putExtra("confidence", confidence)
                putExtra("sequence", sequence)
                putExtra("sensor_time_ms", millis)
                putExtra("steps_total", estimatedStepsTotal)

                // Compatibilidad con parsing legado en JSON
                putExtra("data", JSONObject().apply {
                    put("act", label)
                    put("stp", estimatedStepsTotal)
                    put("conf", confidence)
                    put("seq", sequence)
                    put("t_ms", millis)
                }.toString())
            }
            sendBroadcast(intent)
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
