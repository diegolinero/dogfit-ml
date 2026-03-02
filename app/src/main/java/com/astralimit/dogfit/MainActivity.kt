package com.astralimit.dogfit

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.astralimit.dogfit.ui.theme.DogFitTheme
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val BLE_ACTION_NEW_DATA = "com.astralimit.dogfit.NEW_DATA"
        private const val BLE_ACTION_STATUS = "com.astralimit.dogfit.BLE_STATUS"
        private const val BLE_ACTION_BATTERY = "com.astralimit.dogfit.BLE_BATTERY"
        private const val BLE_EXTRA_CONNECTED = "connected"
        private const val BLE_ACTION_MODE_CHANGED = "com.astralimit.dogfit.MODE_CHANGED"
        private const val PREFS_BATTERY_DIALOG = "dogfit_battery_dialog"
        private const val KEY_LAST_PROMPT_AT = "last_prompt_at"
        private const val BATTERY_PROMPT_COOLDOWN_MS = 24 * 60 * 60 * 1000L
    }

    private val viewModel: DogFitViewModel by viewModels {
        androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(application)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { granted -> !granted }.keys
        if (denied.isNotEmpty()) {
            Log.e(TAG, "Permisos BLE denegados: $denied")
            return@registerForActivityResult
        }
        Log.i(TAG, "Permisos BLE otorgados")
        ensureBleReadyAndStartService()
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.i(TAG, "Resultado enable Bluetooth: resultCode=${result.resultCode}")
        ensureBleReadyAndStartService()
    }

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return

            when (intent.action) {
                BLE_ACTION_STATUS -> {
                    val connected = intent.getBooleanExtra(BLE_EXTRA_CONNECTED, false)
                    viewModel.updateBleConnection(connected)
                }

                BLE_ACTION_BATTERY -> {
                    val batteryRaw = intent.getIntExtra("battery_percent", -1)
                    val battery = batteryRaw.takeIf { it in 0..100 }
                    Log.d(TAG, "BLE batería BAS: raw=$batteryRaw parsed=${battery ?: "sin-cambio"}")
                    viewModel.updateBattery(battery)
                }

                BLE_ACTION_MODE_CHANGED -> {
                    val success = intent.getBooleanExtra(DogFitBleService.EXTRA_SUCCESS, false)
                    val mode = intent.getIntExtra(DogFitBleService.EXTRA_MODE, BlePacketParser.MODE_INFERENCE)
                    if (success) {
                        viewModel.updateMode(mode)
                    }
                    val message = if (success) {
                        if (mode == BlePacketParser.MODE_CAPTURE) "Modo cambiado a CAPTURA" else "Modo cambiado a INFERENCIA"
                    } else {
                        intent.getStringExtra(DogFitBleService.EXTRA_ERROR) ?: "No se pudo cambiar modo"
                    }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                }

                BLE_ACTION_NEW_DATA -> {
                    // Si recibimos datos del collar, forzamos estado conectado en UI.
                    // Esto evita quedar en "desconectado" cuando el broadcast de estado
                    // se emitió antes de registrar el receiver de la Activity.
                    viewModel.updateBleConnection(true)

                    // Firmware (binario -> extras)
                    if (intent.getBooleanExtra("is_capture", false)) {
                        val ax = intent.getIntExtra("ax", 0)
                        val ay = intent.getIntExtra("ay", 0)
                        val az = intent.getIntExtra("az", 0)
                        val gx = intent.getIntExtra("gx", 0)
                        val gy = intent.getIntExtra("gy", 0)
                        val gz = intent.getIntExtra("gz", 0)
                        viewModel.updateLiveImu(ax, ay, az, gx, gy, gz)
                        intent.getByteArrayExtra("capture_raw")?.let { dataCaptureManager.addRawSample(it) }
                        Log.d(TAG, "Muestra RAW IMU recibida en UI")
                    } else if (intent.hasExtra("activity_label")) {
                        parseFirmwarePayload(intent)
                    } else {
                        // Legacy (JSON string)
                        intent.getStringExtra("data")?.let { parsear(it) }
                    }
                }
            }
        }
    }

    private var isDataReceiverRegistered = false
    private lateinit var dataCaptureManager: DataCaptureManager
    private val capturedSessionsState = mutableStateListOf<CapturedSession>()
    private var batteryOptimizationDialog: AlertDialog? = null

    private val qrLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val qrData = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_DATA).orEmpty()
            if (qrData.isNotBlank()) {
                val qrIntent = Intent(this, DogFitBleService::class.java).apply {
                    action = DogFitBleService.ACTION_CONFIGURE_QR
                    putExtra(DogFitBleService.EXTRA_QR_DATA, qrData)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(qrIntent) else startService(qrIntent)
                Toast.makeText(this, "Configuración QR aplicada", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        dataCaptureManager = DataCaptureManager(this)
        capturedSessionsState.clear()
        lifecycleScope.launch {
            val capturedSessions = withContext(Dispatchers.IO) {
                dataCaptureManager.listCapturedSessions()
            }
            capturedSessionsState.addAll(capturedSessions)
        }

        setContent {
            DogFitTheme {
                MainScreen(
                    viewModel = viewModel,
                    onNavigateToProfile = { startActivity(Intent(this, PetProfileScreen::class.java)) },
                    onNavigateToHistory = { startActivity(Intent(this, HistoryScreen::class.java)) },
                    onNavigateToRoutes = { startActivity(Intent(this, RoutesScreen::class.java)) },
                    onNavigateToHealth = { startActivity(Intent(this, DogHealthActivity::class.java)) },
                    onNavigateToAlerts = { startActivity(Intent(this, AlertsActivity::class.java)) },
                    onModeToggle = { mode ->
                        val modeIntent = Intent(this, DogFitBleService::class.java).apply {
                            action = DogFitBleService.ACTION_SET_MODE
                            putExtra(DogFitBleService.EXTRA_MODE, mode)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(modeIntent) else startService(modeIntent)
                    },
                    capturedSessions = capturedSessionsState,
                    onScanQr = { qrLauncher.launch(Intent(this, QrScannerActivity::class.java)) },
                    onCaptureToggle = captureToggle@{ selectedLabel, labelId, customLabel, start ->
                        if (start) {
                            if (customLabel.isBlank()) {
                                Toast.makeText(this, "Completa la etiqueta personalizada", Toast.LENGTH_SHORT).show()
                                return@captureToggle
                            }
                            dataCaptureManager.setActivityLabel(selectedLabel, labelId)
                            dataCaptureManager.setCustomLabel(customLabel)
                            dataCaptureManager.startCapture()
                            getSharedPreferences("dogfit_capture_prefs", Context.MODE_PRIVATE)
                                .edit()
                                .putString("last_custom_label", customLabel)
                                .apply()
                            Toast.makeText(this, "Captura iniciada", Toast.LENGTH_SHORT).show()
                        } else {
                            val file = dataCaptureManager.stopCapture()
                            if (file != null) {
                                capturedSessionsState.clear()
                                capturedSessionsState.addAll(dataCaptureManager.listCapturedSessions())
                                Toast.makeText(this, "Captura guardada: ${file.name}", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this, "No hubo muestras para guardar", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    onExportAll = {
                        val shareIntent = dataCaptureManager.buildShareAllIntent()
                        if (shareIntent != null) {
                            startActivity(Intent.createChooser(shareIntent, "Exportar CSV"))
                        } else {
                            Toast.makeText(this, "No hay archivos para exportar", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onOpenCapture = { session ->
                        val shareIntent = dataCaptureManager.buildShareIntent(session.file)
                        startActivity(Intent.createChooser(shareIntent, "Compartir captura"))
                    }
                )
            }
        }

        handleNotificationIntent(intent)
        ensureBleReadyAndStartService()
    }

    override fun onStart() {
        super.onStart()
        registerDataReceiverIfNeeded()
    }

    override fun onStop() {
        batteryOptimizationDialog?.dismiss()
        batteryOptimizationDialog = null
        if (isDataReceiverRegistered) {
            try {
                unregisterReceiver(dataReceiver)
            } catch (_: IllegalArgumentException) {
                // ya estaba desregistrado
            }
            isDataReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        requestIgnoreBatteryOptimizations()
    }

    private fun registerDataReceiverIfNeeded() {
        if (isDataReceiverRegistered) return

        val filter = IntentFilter().apply {
            addAction(BLE_ACTION_NEW_DATA)
            addAction(BLE_ACTION_STATUS)
            addAction(BLE_ACTION_BATTERY)
            addAction(BLE_ACTION_MODE_CHANGED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dataReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dataReceiver, filter)
        }

        isDataReceiverRegistered = true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNotificationIntent(intent)
    }

    private fun handleNotificationIntent(intent: Intent?) {
        when (intent?.getStringExtra("navigate_to")) {
            "pet_profile" -> startActivity(Intent(this, PetProfileScreen::class.java))
        }
    }

    /**
     * Legacy JSON payload: {"act":..,"stp":..,"bat":..,"lat":..,"lng":..}
     */
    private fun parsear(jsonString: String) {
        try {
            Log.d(TAG, "BLE paquete completo (legacy): $jsonString")
            val json = JSONObject(jsonString)
            val activity = json.optInt("act", viewModel.getActivityValue() ?: 0)
            val steps = json.optInt("stp", 0)
            val batteryRaw = json.optInt("bat", -1)
            val battery = batteryRaw.takeIf { it in 0..100 }

            viewModel.updateActivity(activity)
            viewModel.updateStepsFromBle(steps)

            if (json.has("bat")) {
                Log.d(TAG, "BLE batería parseada (legacy): raw=$batteryRaw parsed=${battery ?: "sin-cambio"}")
                viewModel.updateBattery(battery)
            }

            if (json.has("lat") && json.has("lng")) {
                val lat = json.getDouble("lat")
                val lng = json.getDouble("lng")
                val date = json.optString("date", "")
                val time = json.optString("time", "")
                viewModel.addGpsLocation(lat, lng, date, time)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing BLE data", e)
        }
    }

    /**
     * Firmware extras:
     * - activity_label (Int)
     * - confidence (Int)
     * - sequence (Long/Int)
     * - sensor_time_ms (Long/Int)
     * - steps_total (Int)
     *
     * OJO: aquí NO sumamos “minutos”. Solo pasamos estado/steps al VM.
     * El cálculo de minutos debe hacerse en el ViewModel (usando sensor_time_ms).
     */
    private fun parseFirmwarePayload(intent: Intent) {

        val activity = intent.getIntExtra(
            "activity_label",
            viewModel.getActivityValue() ?: 0
        )

        val stepsTotal = intent.getIntExtra("steps_total", 0)
        val confidence = intent.getIntExtra("confidence", 0)
        val sensorTimeMs = intent.getLongExtra("sensor_time_ms", 0L)
        val batteryRaw = intent.getIntExtra("battery_percent", -1)
        val battery = batteryRaw.takeIf { it in 0..100 }
        val sequence = intent.getLongExtra("sequence", -1L)
        val isLive = intent.getBooleanExtra("is_live", false)

        Log.d(
            TAG,
            "BLE paquete completo (firmware): act=$activity conf=$confidence bat_raw=$batteryRaw seq=$sequence sensor_time_ms=$sensorTimeMs steps_total=$stepsTotal is_live=$isLive"
        )
        Log.d(TAG, "BLE batería parseada (firmware): raw=$batteryRaw parsed=${battery ?: "sin-cambio"}")

        if (sensorTimeMs > 0L) {
            viewModel.onBleSample(activity, confidence, sensorTimeMs)
        } else {
            viewModel.updateActivity(activity)
        }
        viewModel.updateLiveClassification(activity, confidence)

        if (intent.hasExtra("steps_total")) {
            viewModel.updateStepsFromBle(stepsTotal)
        }
        viewModel.updateBattery(battery)
    }

    private fun ensureBleReadyAndStartService() {
        if (!checkPermissions()) {
            Log.w(TAG, "Permisos BLE faltantes antes de iniciar escaneo/conexión")
            requestPermissions()
            return
        }

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = btManager.adapter

        if (adapter == null) {
            Log.e(TAG, "BluetoothAdapter no disponible en este dispositivo")
            return
        }

        if (!adapter.isEnabled) {
            Log.w(TAG, "Bluetooth está apagado; solicitando habilitar")
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }

        Log.i(TAG, "Permisos y Bluetooth OK, iniciando DogFitBleService")

        val serviceIntent = Intent(this, DogFitBleService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        if (isFinishing || isDestroyed) return

        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        val packageName = packageName

        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return
        if (batteryOptimizationDialog?.isShowing == true) return

        val promptPrefs = getSharedPreferences(PREFS_BATTERY_DIALOG, Context.MODE_PRIVATE)
        val lastPromptAt = promptPrefs.getLong(KEY_LAST_PROMPT_AT, 0L)
        val now = System.currentTimeMillis()
        if (now - lastPromptAt < BATTERY_PROMPT_COOLDOWN_MS) return

        batteryOptimizationDialog = AlertDialog.Builder(this)
            .setTitle("Optimización de batería")
            .setMessage("Para evitar desconexiones cuando la pantalla está apagada, permite que DogFit ignore optimizaciones de batería. ¿Quieres configurarlo ahora?")
            .setPositiveButton("Sí") { _, _ ->
                promptPrefs.edit().putLong(KEY_LAST_PROMPT_AT, System.currentTimeMillis()).apply()
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton("Ahora no") { _, _ ->
                promptPrefs.edit().putLong(KEY_LAST_PROMPT_AT, System.currentTimeMillis()).apply()
            }
            .show()
    }

    private fun checkPermissions(): Boolean {
        // ✅ BLE debe funcionar incluso si el usuario no concede POST_NOTIFICATIONS.
        // Por eso aquí validamos solo permisos estrictamente necesarios para escaneo/conexión.
        val blePermissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            blePermissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            blePermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            blePermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        return blePermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        // Solicitamos permisos mínimos de BLE para no bloquear conexión por permisos opcionales.
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        Log.i(TAG, "Solicitando permisos runtime BLE: $permissions")
        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        try {
            if (isDataReceiverRegistered) {
                unregisterReceiver(dataReceiver)
                isDataReceiverRegistered = false
            }
        } catch (_: IllegalArgumentException) {
            // ya estaba desregistrado
        }
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: DogFitViewModel,
    onNavigateToProfile: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToRoutes: () -> Unit,
    onNavigateToHealth: () -> Unit,
    onNavigateToAlerts: () -> Unit,
    onModeToggle: (Int) -> Unit,
    capturedSessions: List<CapturedSession>,
    onScanQr: () -> Unit,
    onCaptureToggle: (String, Int, String, Boolean) -> Unit,
    onExportAll: () -> Unit,
    onOpenCapture: (CapturedSession) -> Unit
) {
    val scrollState = rememberScrollState()
    val profile by viewModel.dogProfile.observeAsState()
    val batteryValue by viewModel.batteryValue.collectAsState()
    val activityValue by viewModel.activityValue.collectAsState()
    val activityTimes by viewModel.activityTimes.collectAsState()
    val alerts by viewModel.alerts.observeAsState()
    val bleConnected by viewModel.bleConnected.collectAsState()
    val currentMode by viewModel.currentMode.collectAsState()
    val liveLabel by viewModel.liveLabel.collectAsState()
    val liveConfidence by viewModel.liveConfidence.collectAsState()
    val liveImu by viewModel.liveImuAxes.collectAsState()
    val context = LocalContext.current
    val labels = listOf("Descanso", "Caminar", "Correr", "Escaleras")
    var selectedLabel by remember { mutableStateOf(labels.first()) }
    var customLabel by remember {
        mutableStateOf(
            context.getSharedPreferences("dogfit_capture_prefs", Context.MODE_PRIVATE)
                .getString("last_custom_label", "")
                .orEmpty()
        )
    }
    var capturing by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    val currentStateLabel = when (activityValue) {
        0 -> "Caminando"
        1 -> "Corriendo"
        2 -> "Reposo"
        3 -> "Jugando"
        else -> "Sin datos"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "DogFit",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = profile?.name ?: "Tu mascota",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToAlerts) {
                        BadgedBox(
                            badge = {
                                val pendingAlerts = alerts?.size ?: 0
                                if (pendingAlerts > 0) Badge { Text("$pendingAlerts") }
                            }
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = "Alertas")
                        }
                    }
                    IconButton(onClick = onNavigateToProfile) {
                        Icon(Icons.Default.Pets, contentDescription = "Perfil")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            ActivityDistributionCard(
                activityTimes = activityTimes,
                currentStateLabel = currentStateLabel
            )

            TotalAccumulatedTimeCard(activityTimes = activityTimes)


            Button(
                onClick = {
                    if (!bleConnected) {
                        Toast.makeText(context, "Dispositivo desconectado", Toast.LENGTH_SHORT).show()
                    } else {
                        val nextMode = if (currentMode == BlePacketParser.MODE_INFERENCE) {
                            BlePacketParser.MODE_CAPTURE
                        } else {
                            BlePacketParser.MODE_INFERENCE
                        }
                        onModeToggle(nextMode)
                    }
                },
                enabled = bleConnected,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (currentMode == BlePacketParser.MODE_INFERENCE) {
                        "🔄 Modo: INFERENCIA"
                    } else {
                        "🔄 Modo: CAPTURA"
                    }
                )
            }

            Button(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
                Text("Escanear QR")
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tiempo real", fontWeight = FontWeight.Bold)
                    Text("Label: $liveLabel")
                    Text("Confianza: $liveConfidence%")
                    LinearProgressIndicator(progress = { liveConfidence / 100f }, modifier = Modifier.fillMaxWidth())
                    Text("IMU ax:${liveImu[0]} ay:${liveImu[1]} az:${liveImu[2]} gx:${liveImu[3]} gy:${liveImu[4]} gz:${liveImu[5]}")
                }
            }

            if (currentMode == BlePacketParser.MODE_CAPTURE) {
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth().clickable { expanded = true },
                        label = { Text("Actividad") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        labels.forEach { label ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { selectedLabel = label; expanded = false })
                        }
                    }
                }

                OutlinedTextField(
                    value = customLabel,
                    onValueChange = { customLabel = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Etiqueta personalizada") },
                    placeholder = { Text("Nombre del perro / superficie / notas") }
                )

                Button(
                    onClick = {
                        if (!bleConnected) {
                            Toast.makeText(context, "Conecta el dispositivo", Toast.LENGTH_SHORT).show()
                        } else {
                            val labelId = labels.indexOf(selectedLabel).coerceAtLeast(0)
                            val start = !capturing
                            onCaptureToggle(selectedLabel, labelId, customLabel, start)
                            capturing = start
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = bleConnected
                ) { Text(if (capturing) "Detener captura" else "Iniciar captura") }

                Button(onClick = onExportAll, modifier = Modifier.fillMaxWidth()) { Text("Exportar datos") }

                Text("Capturas CSV", fontWeight = FontWeight.Bold)
                capturedSessions.forEach { session ->
                    Text(
                        text = "• ${session.file.name} (${session.fullLabel})",
                        modifier = Modifier.fillMaxWidth().clickable { onOpenCapture(session) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatusCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Speed,
                    label = "Estado",
                    value = if (bleConnected) "Conectado" else "Desconectado",
                    color = MaterialTheme.colorScheme.secondaryContainer
                )

                StatusCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.BatteryFull,
                    label = "Batería",
                    value = "${batteryValue ?: "--"}%",
                    color = MaterialTheme.colorScheme.tertiaryContainer
                )
            }

            Text(
                text = "Acceso Rápido",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickAccessCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.History,
                    label = "Historial",
                    onClick = onNavigateToHistory
                )
                QuickAccessCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Map,
                    label = "Rutas",
                    onClick = onNavigateToRoutes
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickAccessCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.MonitorHeart,
                    label = "Salud",
                    onClick = onNavigateToHealth
                )
                QuickAccessCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Badge,
                    label = "Ficha",
                    onClick = onNavigateToProfile
                )
            }
        }
    }
}

@Composable
fun ActivityDistributionCard(
    activityTimes: Map<Int, Long>,
    currentStateLabel: String
) {
    val restSeconds = activityTimes[0] ?: 0L
    val walkSeconds = activityTimes[1] ?: 0L
    val runSeconds = activityTimes[2] ?: 0L
    val playSeconds = activityTimes[3] ?: 0L
    val totalSeconds = (restSeconds + walkSeconds + runSeconds + playSeconds).coerceAtLeast(1L)

    val segments = listOf(
        restSeconds to Color(0xFF9E9E9E),
        walkSeconds to Color(0xFF4CAF50),
        runSeconds to Color(0xFFFFC107),
        playSeconds to Color(0xFFFF5722)
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Distribución de Actividad",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(18.dp))

            Box(modifier = Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val stroke = 28.dp.toPx()
                    val gapDegrees = 2f
                    var startAngle = -90f

                    segments.forEach { (seconds, color) ->
                        val sweep = ((seconds.toFloat() / totalSeconds.toFloat()) * 360f - gapDegrees).coerceAtLeast(0f)
                        if (sweep > 0f) {
                            drawArc(
                                color = color,
                                startAngle = startAngle,
                                sweepAngle = sweep,
                                useCenter = false,
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                        }
                        startAngle += (sweep + gapDegrees)
                    }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = currentStateLabel,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "ESTADO ACTUAL",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun TotalAccumulatedTimeCard(activityTimes: Map<Int, Long>) {
    val elapsedTodaySeconds = remember {
        val now = Calendar.getInstance()
        (now.get(Calendar.HOUR_OF_DAY) * 3600L) +
            (now.get(Calendar.MINUTE) * 60L) +
            now.get(Calendar.SECOND)
    }.coerceAtLeast(1L)

    val rawTotal = (activityTimes[0] ?: 0L) + (activityTimes[1] ?: 0L) + (activityTimes[2] ?: 0L) + (activityTimes[3] ?: 0L)
    val capFactor = if (rawTotal > elapsedTodaySeconds) {
        elapsedTodaySeconds.toFloat() / rawTotal.toFloat()
    } else {
        1f
    }

    fun capped(type: Int): Long = ((activityTimes[type] ?: 0L) * capFactor).toLong()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(32.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Tiempo Total Acumulado",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            TimeLegendRow(label = "Reposo", color = Color(0xFF9E9E9E), seconds = capped(0))
            TimeLegendRow(label = "Caminando", color = Color(0xFF4CAF50), seconds = capped(1))
            TimeLegendRow(label = "Corriendo", color = Color(0xFFFFC107), seconds = capped(2))
            TimeLegendRow(label = "Jugando", color = Color(0xFFFF5722), seconds = capped(3))
        }
    }
}

@Composable
fun TimeLegendRow(label: String, color: Color, seconds: Long) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        Text(
            text = seconds.toElapsedTime(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun Long.toElapsedTime(): String {
    val hours = TimeUnit.SECONDS.toHours(this)
    val minutes = TimeUnit.SECONDS.toMinutes(this) % 60
    val seconds = this % 60
    return "${hours}h ${minutes}m ${seconds}s"
}

@Composable
fun StatusCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun MetricCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    value: String,
    label: String
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun QuickAccessCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier.clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
