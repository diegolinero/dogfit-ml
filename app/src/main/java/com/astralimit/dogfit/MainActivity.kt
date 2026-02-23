package com.astralimit.dogfit

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astralimit.dogfit.ui.theme.DogFitTheme
import org.json.JSONObject
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val BLE_ACTION_NEW_DATA = "com.astralimit.dogfit.NEW_DATA"
        private const val BLE_ACTION_STATUS = "com.astralimit.dogfit.BLE_STATUS"
        private const val BLE_EXTRA_CONNECTED = "connected"
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
                    viewModel.updateBleConnection(
                        intent.getBooleanExtra(BLE_EXTRA_CONNECTED, false)
                    )
                }
                BLE_ACTION_NEW_DATA -> {
                    if (intent.hasExtra("activity_label")) {
                        parseFirmwarePayload(intent)
                    } else {
                        intent.getStringExtra("data")?.let { parsear(it) }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            DogFitTheme {
                MainScreen(
                    viewModel = viewModel,
                    onNavigateToProfile = {
                        startActivity(Intent(this, PetProfileScreen::class.java))
                    },
                    onNavigateToHistory = {
                        startActivity(Intent(this, HistoryScreen::class.java))
                    },
                    onNavigateToRoutes = {
                        startActivity(Intent(this, RoutesScreen::class.java))
                    },
                    onNavigateToHealth = {
                        startActivity(Intent(this, DogHealthActivity::class.java))
                    },
                    onNavigateToAlerts = {
                        startActivity(Intent(this, AlertsActivity::class.java))
                    }
                )
            }
        }

        handleNotificationIntent(intent)
        ensureBleReadyAndStartService()
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

    private fun parsear(jsonString: String) {
        try {
            val json = JSONObject(jsonString)
            val activity = json.optInt("act", viewModel.getActivityValue() ?: 0)
            val steps = json.optInt("stp", 0)

            viewModel.updateActivity(activity)
            viewModel.updateStepsFromBle(steps)

            if (json.has("bat")) {
                viewModel.updateBattery(json.optInt("bat", viewModel.getBatteryValue() ?: 0))
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

    private fun parseFirmwarePayload(intent: Intent) {
        val activity = intent.getIntExtra("activity_label", viewModel.getActivityValue() ?: 0)
        val stepsTotal = intent.getIntExtra("steps_total", 0)

        viewModel.updateActivity(activity)
        viewModel.updateStepsFromBle(stepsTotal)
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
        startService(Intent(this, DogFitBleService::class.java))
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        Log.i(TAG, "Solicitando permisos runtime: $permissions")
        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(BLE_ACTION_NEW_DATA)
            addAction(BLE_ACTION_STATUS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(dataReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(dataReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(dataReceiver)
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
    onNavigateToAlerts: () -> Unit
) {
    val scrollState = rememberScrollState()
    val profile by viewModel.dogProfile.observeAsState()
    val dailyStats by viewModel.dailyStats.observeAsState()
    val batteryValue by viewModel.batteryValue.collectAsState()
    val activityValue by viewModel.activityValue.collectAsState()
    val alerts by viewModel.alerts.observeAsState()
    val bleConnected by viewModel.bleConnected.collectAsState()

    val targetSteps = profile?.targetActiveMinutes ?: 5000
    val currentSteps = dailyStats?.totalActiveMinutes ?: 0
    val progress = (currentSteps.toFloat() / targetSteps).coerceIn(0f, 1f)

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
                                if (pendingAlerts > 0) {
                                    Badge { Text("$pendingAlerts") }
                                }
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                shape = RoundedCornerShape(32.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxSize(),
                            strokeWidth = 12.dp,
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "$currentSteps",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "MIN",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "${(progress * 100).toInt()}% del objetivo",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Meta: $targetSteps min",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    value = if (!bleConnected) {
                        "Desconectado"
                    } else {
                        when (activityValue) {
                            0 -> "Reposo"
                            1 -> "Caminando"
                            2 -> "Corriendo"
                            3 -> "Jugando"
                            else -> "Conectado"
                        }
                    },
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.LocalFireDepartment,
                    value = String.format("%.0f", dailyStats?.caloriesBurned ?: 0f),
                    label = "Calorías"
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Route,
                    value = String.format("%.1f", dailyStats?.distanceKm ?: 0f),
                    label = "Km"
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Timer,
                    value = "${dailyStats?.activeMinutes ?: 0}",
                    label = "Min activos"
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