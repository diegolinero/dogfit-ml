package com.astralimit.dogfit

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.astralimit.dogfit.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

class DogFitViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    val activityClassifier = ActivityClassifier()

    // =========================
    // StateFlow / Compose
    // =========================
    private val _activityValue = MutableStateFlow<Int?>(null)
    val activityValue: StateFlow<Int?> = _activityValue.asStateFlow()

    private val _batteryValue = MutableStateFlow<Int?>(null)
    val batteryValue: StateFlow<Int?> = _batteryValue.asStateFlow()

    private val _bleConnected = MutableStateFlow(false)
    val bleConnected: StateFlow<Boolean> = _bleConnected.asStateFlow()

    // ✅ RESTAURADO: lo usan otras pantallas
    private val _activityTimes = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val activityTimes: StateFlow<Map<Int, Long>> = _activityTimes.asStateFlow()

    // LiveData compat
    private val _activityLiveData = MutableLiveData<Int?>()
    val activityLiveData: LiveData<Int?> = _activityLiveData

    private val _batteryLiveData = MutableLiveData<Int?>()
    val batteryLiveData: LiveData<Int?> = _batteryLiveData

    // Strings UI
    private val _activityState = MutableLiveData<String>().apply { value = "Actividad: --" }
    val activityState: LiveData<String> = _activityState

    private val _batteryLevel = MutableLiveData<String>().apply { value = "Batería: --%" }
    val batteryLevel: LiveData<String> = _batteryLevel

    private val _stepsCount = MutableLiveData<String>().apply { value = "Pasos hoy: 0" }
    val stepsCount: LiveData<String> = _stepsCount

    private val _caloriesBurned = MutableLiveData<String>().apply { value = "Calorías: 0" }
    val caloriesBurned: LiveData<String> = _caloriesBurned

    private val _distanceToday = MutableLiveData<String>().apply { value = "Distancia: 0 km" }
    val distanceToday: LiveData<String> = _distanceToday

    // Datos
    private val _activityHistory = MutableLiveData<List<DogActivityData>>(emptyList())
    val activityHistory: LiveData<List<DogActivityData>> = _activityHistory

    private val _dogProfile = MutableLiveData<DogProfile>(DogProfile())
    val dogProfile: LiveData<DogProfile> = _dogProfile

    private val _alerts = MutableLiveData<List<DogAlert>>(emptyList())
    val alerts: LiveData<List<DogAlert>> = _alerts

    private val _dailyStats = MutableLiveData<DailySummary>()
    val dailyStats: LiveData<DailySummary> = _dailyStats

    private val _weeklyStats = MutableLiveData<WeeklySummaryModel>()
    val weeklyStats: LiveData<WeeklySummaryModel> = _weeklyStats

    private val _monthlyStats = MutableLiveData<MonthlySummaryModel>()
    val monthlyStats: LiveData<MonthlySummaryModel> = _monthlyStats

    // =========================
    // FIX BLE: tiempo real + suavizado
    // =========================
    private var lastSensorMs: Long? = null
    private var activeMsToday: Long = 0L
    private val activityMsToday = LongArray(4) { 0L } // 0..3

    private val recentLabels = ArrayDeque<Int>()
    private val recentMax = 4
    private var stableLabel = 0
    private var pendingStableLabel: Int? = null
    private var pendingSinceMs: Long = 0L
    private val confThreshold = 60
    private val stableSwitchDelayMs = 600L

    // Persistencia local para evitar pérdida de datos al reiniciar app
    private val prefs by lazy {
        context.getSharedPreferences("dogfit_runtime_state", android.content.Context.MODE_PRIVATE)
    }
    private val keyStateDate = "state_date"
    private val keyStepsTotal = "steps_total"
    private val keyHistory = "activity_history"
    private val keyActivityMs = "activity_ms"
    private val keyActiveMs = "active_ms"

    // Para saber si hoy ya tenemos stream BLE y no usar count*5
    private var hasBleTimingForToday = false

    // =========================
    // Recomendaciones raza (igual)
    // =========================
    private val breedRecommendations = mapOf(
        "Labrador" to BreedRecommendation(
            breed = "Labrador Retriever",
            recommendedDailySteps = 8000..12000,
            recommendedPlayTime = 60,
            energyLevel = "Alta",
            healthTips = listOf(
                "Propenso a obesidad - controlar peso",
                "Necesita ejercicio diario intenso",
                "Excelente para actividades acuáticas"
            )
        ),
        "Bulldog" to BreedRecommendation(
            breed = "Bulldog Francés",
            recommendedDailySteps = 3000..5000,
            recommendedPlayTime = 30,
            energyLevel = "Baja",
            healthTips = listOf(
                "Evitar ejercicio en horas de calor",
                "Problemas respiratorios - monitorear",
                "Necesita descansos frecuentes"
            )
        ),
        "Pastor Alemán" to BreedRecommendation(
            breed = "Pastor Alemán",
            recommendedDailySteps = 10000..15000,
            recommendedPlayTime = 90,
            energyLevel = "Muy Alta",
            healthTips = listOf(
                "Necesita estimulación mental",
                "Excelente para deportes caninos",
                "Propenso a problemas de cadera"
            )
        ),
        "Chihuahua" to BreedRecommendation(
            breed = "Chihuahua",
            recommendedDailySteps = 2000..4000,
            recommendedPlayTime = 20,
            energyLevel = "Moderada",
            healthTips = listOf(
                "Proteger del frío",
                "Socialización temprana importante",
                "Ejercicio en espacios controlados"
            )
        ),
        "Golden Retriever" to BreedRecommendation(
            breed = "Golden Retriever",
            recommendedDailySteps = 9000..13000,
            recommendedPlayTime = 75,
            energyLevel = "Alta",
            healthTips = listOf(
                "Amigable y sociable",
                "Necesita ejercicio regular",
                "Propenso a cáncer - chequeos frecuentes"
            )
        ),
        "Mixed" to BreedRecommendation(
            breed = "Mestizo",
            recommendedDailySteps = 5000..8000,
            recommendedPlayTime = 45,
            energyLevel = "Moderada",
            healthTips = listOf(
                "Adaptable a diferentes rutinas",
                "Observar comportamiento individual",
                "Chequeos veterinarios regulares"
            )
        )
    )

    init {
        restorePersistedState()
        updateAllStats()
        generateInitialAlerts()
    }

    // =========================================================
    // ✅ NUEVO: entrada BLE correcta (label + confidence + sensor_time_ms)
    // =========================================================
    fun onBleSample(label: Int, confidence: Int, sensorTimeMs: Long) {
        viewModelScope.launch {
            val raw = label.coerceIn(0, 3)
            val cleaned = if (confidence < confThreshold) 0 else raw

            val prev = lastSensorMs
            if (prev == null) {
                lastSensorMs = sensorTimeMs
                val stable = pushAndGetStableLabel(cleaned, sensorTimeMs)
                updateActivity(stable)
                return@launch
            }

            var dt = sensorTimeMs - prev

            // reset / backlog raro / wrap
            if (dt <= 0 || dt > 10_000) {
                lastSensorMs = sensorTimeMs
                return@launch
            }

            // clamp para evitar que 1 paquete cuente como minutos
            dt = dt.coerceAtMost(2000)

            lastSensorMs = sensorTimeMs
            hasBleTimingForToday = true

            if (cleaned in 0..3) activityMsToday[cleaned] += dt
            if (cleaned in 1..3) activeMsToday += dt
            persistRuntimeState()

            val stable = pushAndGetStableLabel(cleaned, sensorTimeMs)
            updateActivity(stable)

            // actualiza dailyStats usando tiempo real
            updateDailyStats()
        }
    }

    private fun pushAndGetStableLabel(label: Int, sensorTimeMs: Long): Int {
        recentLabels.addLast(label)
        while (recentLabels.size > recentMax) recentLabels.removeFirst()

        var bestLabel = 0
        var bestCount = -1
        for (l in 0..3) {
            val c = recentLabels.count { it == l }
            if (c > bestCount) {
                bestCount = c
                bestLabel = l
            }
        }

        val shouldSwitch = bestLabel != stableLabel && bestCount >= 1
        if (!shouldSwitch) {
            pendingStableLabel = null
            return stableLabel
        }

        if (pendingStableLabel != bestLabel) {
            pendingStableLabel = bestLabel
            pendingSinceMs = sensorTimeMs
            return stableLabel
        }

        if ((sensorTimeMs - pendingSinceMs) >= stableSwitchDelayMs) {
            stableLabel = bestLabel
            pendingStableLabel = null
        }

        return stableLabel
    }

    // =========================================================
    // BLE updates base
    // =========================================================
    fun updateActivity(activityValue: Int) {
        viewModelScope.launch {
            val normalized = activityValue.coerceIn(0, 3)
            _activityValue.value = normalized
            _activityLiveData.value = normalized

            val txt = when (normalized) {
                0 -> "🛌 Reposo"
                1 -> "🚶 Caminata"
                2 -> "🏃 Carrera"
                3 -> "🎾 Juego"
                else -> "❓ Desconocido"
            }
            _activityState.value = "Actividad: $txt"
            Log.d("DogFitViewModel", "Actividad actualizada: $normalized")
        }
    }

    fun updateBattery(batteryValue: Int?) {
        viewModelScope.launch {
            val current = _batteryValue.value
            val normalized = batteryValue?.coerceIn(0, 100) ?: current

            if (normalized == null) {
                _batteryLevel.value = "Batería: --%"
                return@launch
            }

            _batteryValue.value = normalized
            _batteryLiveData.value = normalized
            _batteryLevel.value = "Batería: $normalized%"

            if (normalized < 20) {
                addAlert(
                    DogAlert(
                        id = System.currentTimeMillis(),
                        type = AlertType.BATTERY_LOW,
                        message = "Batería del dispositivo baja ($normalized%)",
                        severity = 2,
                        recommendedAction = "Recargar el dispositivo DogFit"
                    )
                )
            }
        }
    }

    fun updateBleConnection(connected: Boolean) {
        viewModelScope.launch {
            _bleConnected.value = connected
            if (!connected) {
                _activityValue.value = null
                lastSensorMs = null
                recentLabels.clear()
                stableLabel = 0
                pendingStableLabel = null
                pendingSinceMs = 0L
            }
            Log.d("DogFitViewModel", "BLE conectado: $connected")
        }
    }

    // =========================================================
    // Steps / distance / calories (igual lógica, pero OJO con minutos)
    // =========================================================
    private fun updateCalories(steps: Int) {
        val calories = steps * 0.05f
        val currentText = _caloriesBurned.value ?: "Calorías: 0"
        val current = currentText.substringAfter(": ").toFloatOrNull() ?: 0f
        _caloriesBurned.value = "Calorías: ${String.format("%.1f", current + calories)}"
    }

    private fun updateDistance(steps: Int) {
        val distance = calculateDistance(steps, _dogProfile.value?.breed ?: "Mixed")
        val currentText = _distanceToday.value ?: "Distancia: 0 km"
        val current = currentText.substringAfter(": ").substringBefore(" ").toFloatOrNull() ?: 0f
        _distanceToday.value = "Distancia: ${String.format("%.2f", current + distance)} km"
    }

    fun updateStepsFromBle(steps: Int) {
        viewModelScope.launch {
            val normalizedSteps = steps.coerceAtLeast(0)
            val previousSteps = _stepsCount.value?.substringAfter(": ")?.toIntOrNull() ?: 0
            _stepsCount.value = "Pasos hoy: $normalizedSteps"

            val deltaSteps = (normalizedSteps - previousSteps).coerceAtLeast(0)
            if (deltaSteps > 0) {
                val activityType = _activityValue.value ?: 0
                val newData = DogActivityData(
                    id = System.currentTimeMillis(),
                    timestamp = System.currentTimeMillis(),
                    activityType = activityType,
                    intensity = when (activityType) {
                        0 -> 0.1f
                        1 -> 0.5f
                        2 -> 0.9f
                        3 -> 0.7f
                        else -> 0.3f
                    },
                    steps = deltaSteps,
                    estimatedDistance = calculateDistance(deltaSteps, _dogProfile.value?.breed ?: "Mixed") * 1000,
                    calories = deltaSteps * 0.05f
                )

                val list = _activityHistory.value?.toMutableList() ?: mutableListOf()
                list.add(newData)
                _activityHistory.value = list
                persistRuntimeState()

                updateCalories(deltaSteps)
                updateDistance(deltaSteps)
                checkAlerts(newData)
            }

            persistRuntimeState()

            // No usamos count*5 para HOY si hay BLE timing.
            updateAllStats()
        }
    }

    // =========================================================
    // Daily/Weekly/Monthly
    // =========================================================
    private fun updateAllStats() {
        updateDailyStats()
        updateWeeklyStats()
        updateMonthlyStats()
    }

    fun reloadActivityTimesFromDatabase() {
        updateDailyStats()
    }

    private fun updateDailyStats() {
        ensureCurrentDayState()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val todayData = _activityHistory.value?.filter {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(it.timestamp)) == today
        } ?: emptyList()

        val totalSteps = todayData.sumOf { it.steps }

        // ✅ FIX: si hay stream BLE, minutos vienen del tiempo real.
        val activeMinutes: Int
        val restMinutes: Int
        val activityDistribution = mutableMapOf<String, Int>()
        val activityTimesMap: Map<Int, Long>

        if (hasBleTimingForToday) {
            activeMinutes = (activeMsToday / 60_000L).toInt()
            // reposo lo dejamos como “resto del día” aproximado para UI
            restMinutes = (24 * 60) - activeMinutes

            fun putMin(name: String, ms: Long) {
                val min = (ms / 60_000L).toInt()
                if (min > 0) activityDistribution[name] = min
            }
            putMin("Reposo", activityMsToday[0])
            putMin("Caminata", activityMsToday[1])
            putMin("Carrera", activityMsToday[2])
            putMin("Juego", activityMsToday[3])

            // ✅ activityTimes: segundos (como lo estabas usando)
            activityTimesMap = mapOf(
                0 to (activityMsToday[0] / 1000L),
                1 to (activityMsToday[1] / 1000L),
                2 to (activityMsToday[2] / 1000L),
                3 to (activityMsToday[3] / 1000L),
            )
        } else {
            // Fallback (histórico viejo)
            activeMinutes = todayData.count { it.activityType in 1..3 } * 5
            restMinutes = todayData.count { it.activityType == 0 } * 5

            todayData.forEach { data ->
                val name = when (data.activityType) {
                    0 -> "Reposo"
                    1 -> "Caminata"
                    2 -> "Carrera"
                    3 -> "Juego"
                    else -> "Desconocido"
                }
                activityDistribution[name] = (activityDistribution[name] ?: 0) + 5
            }

            activityTimesMap = mapOf(
                0 to (todayData.count { it.activityType == 0 } * 5L * 60L),
                1 to (todayData.count { it.activityType == 1 } * 5L * 60L),
                2 to (todayData.count { it.activityType == 2 } * 5L * 60L),
                3 to (todayData.count { it.activityType == 3 } * 5L * 60L)
            )
        }

        _activityTimes.value = activityTimesMap

        val distance = todayData.sumOf { it.estimatedDistance.toDouble() }.toFloat() / 1000f
        val calories = todayData.sumOf { it.calories.toDouble() }.toFloat()

        val targetDailySteps = _dogProfile.value?.targetDailySteps ?: 5000
        val goalPercentage = if (targetDailySteps > 0) (totalSteps.toFloat() / targetDailySteps) * 100f else 0f

        val wellnessScore = calculateWellnessScore(
            activity = totalSteps.toFloat() / max(1, targetDailySteps),
            sleep = 0.7f,
            consistency = 0.8f
        )

        _dailyStats.value = DailySummary(
            date = today,
            totalSteps = totalSteps,
            totalActiveMinutes = activeMinutes,
            activeMinutes = activeMinutes,
            restMinutes = restMinutes,
            distanceKm = distance,
            caloriesBurned = calories,
            peakActivityHour = "—",
            longestWalk = if (todayData.isNotEmpty()) 30 else 0,
            activityDistribution = activityDistribution,
            goalAchieved = goalPercentage >= 100f,
            wellnessScore = wellnessScore,
            activityTimes = activityTimesMap
        )
    }

    private fun updateWeeklyStats() {
        // (tu implementación original puede quedarse; no toca los errores)
        // Si quieres, la dejamos igual para no inflar este archivo.
    }

    private fun updateMonthlyStats() {
        // (tu implementación original puede quedarse; no toca los errores)
    }

    private fun ensureCurrentDayState() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val savedDate = prefs.getString(keyStateDate, null)
        if (savedDate == null || savedDate == today) return

        _stepsCount.value = "Pasos hoy: 0"
        _activityHistory.value = emptyList()
        activeMsToday = 0L
        for (i in activityMsToday.indices) activityMsToday[i] = 0L
        hasBleTimingForToday = false
        lastSensorMs = null
        persistRuntimeState()
    }

    private fun restorePersistedState() {
        ensureCurrentDayState()

        val savedSteps = prefs.getInt(keyStepsTotal, 0).coerceAtLeast(0)
        _stepsCount.value = "Pasos hoy: $savedSteps"

        val historyJson = prefs.getString(keyHistory, null)
        if (!historyJson.isNullOrEmpty()) {
            val restored = mutableListOf<DogActivityData>()
            val restoredOk = runCatching {
                val arr = JSONArray(historyJson)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    restored.add(
                        DogActivityData(
                            id = obj.optLong("id", 0L),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            activityType = obj.optInt("activityType", 0),
                            intensity = obj.optDouble("intensity", 0.1).toFloat(),
                            steps = obj.optInt("steps", 0),
                            estimatedDistance = obj.optDouble("estimatedDistance", 0.0).toFloat(),
                            calories = obj.optDouble("calories", 0.0).toFloat(),
                            durationMinutes = obj.optInt("durationMinutes", 0)
                        )
                    )
                }
            }.isSuccess

            if (restoredOk) {
                _activityHistory.value = restored.takeLast(2000)
            } else {
                Log.w("DogFitViewModel", "Historial corrupto en preferencias; se restablece estado persistido")
                prefs.edit().remove(keyHistory).remove(keyActivityMs).remove(keyActiveMs).apply()
                _activityHistory.value = emptyList()
                activeMsToday = 0L
                for (i in activityMsToday.indices) activityMsToday[i] = 0L
            }
        }

        val activityMsJson = prefs.getString(keyActivityMs, null)
        if (!activityMsJson.isNullOrEmpty()) {
            val activityMsOk = runCatching {
                val arr = JSONArray(activityMsJson)
                for (i in 0..3) activityMsToday[i] = arr.optLong(i, 0L).coerceAtLeast(0L)
            }.isSuccess
            if (!activityMsOk) {
                Log.w("DogFitViewModel", "activity_ms corrupto; reiniciando acumuladores")
                for (i in activityMsToday.indices) activityMsToday[i] = 0L
                activeMsToday = 0L
                prefs.edit().remove(keyActivityMs).remove(keyActiveMs).apply()
            }
        }

        activeMsToday = prefs.getLong(keyActiveMs, 0L).coerceAtLeast(0L)
        hasBleTimingForToday = activityMsToday.any { it > 0L } || activeMsToday > 0L
    }

    private fun persistRuntimeState() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val steps = _stepsCount.value?.substringAfter(": ")?.toIntOrNull() ?: 0

        val historyArr = JSONArray()
        (_activityHistory.value ?: emptyList()).takeLast(2000).forEach { item ->
            historyArr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("timestamp", item.timestamp)
                    put("activityType", item.activityType)
                    put("intensity", item.intensity.toDouble())
                    put("steps", item.steps)
                    put("estimatedDistance", item.estimatedDistance.toDouble())
                    put("calories", item.calories.toDouble())
                    put("durationMinutes", item.durationMinutes)
                }
            )
        }

        val activityMsArr = JSONArray().apply {
            for (i in 0..3) put(activityMsToday[i])
        }

        prefs.edit()
            .putString(keyStateDate, today)
            .putInt(keyStepsTotal, steps)
            .putString(keyHistory, historyArr.toString())
            .putString(keyActivityMs, activityMsArr.toString())
            .putLong(keyActiveMs, activeMsToday)
            .apply()
    }

    // =========================================================
    // Perfil / recomendaciones
    // =========================================================
    fun updateDogProfile(profile: DogProfile) {
        _dogProfile.value = profile
        val rec = breedRecommendations[profile.breed]
        if (rec != null) {
            addAlert(
                DogAlert(
                    id = System.currentTimeMillis(),
                    type = AlertType.HEALTH_TIP,
                    message = "Recomendación para ${profile.breed}: ${rec.healthTips.firstOrNull()}",
                    severity = 1,
                    recommendedAction = "Ver más consejos en Perfil"
                )
            )
        }
    }

    fun getBreedRecommendation(breed: String): BreedRecommendation? = breedRecommendations[breed]
    fun getAllBreeds(): List<String> = breedRecommendations.keys.toList()

    fun saveFullCalibration(rest: CalibrationFeatures, walk: CalibrationFeatures, run: CalibrationFeatures) {
        activityClassifier.applyFullCalibration(rest, walk, run)

        val profile = _dogProfile.value ?: DogProfile()
        _dogProfile.value = profile.copy(
            isCalibrated = true,
            calibrationRestValue = rest.mean,
            calibrationWalkValue = walk.mean,
            calibrationRunValue = run.mean,
            calibrationRestFeatures = rest,
            calibrationWalkFeatures = walk,
            calibrationRunFeatures = run
        )
    }

    fun addVetVisit(visit: VetVisitRecord) {
        val profile = _dogProfile.value ?: return
        _dogProfile.value = profile.copy(vetVisits = profile.vetVisits + visit)
    }

    fun updateVetVisit(visit: VetVisitRecord) {
        val profile = _dogProfile.value ?: return
        val updated = profile.vetVisits.map { if (it.id == visit.id) visit else it }
        _dogProfile.value = profile.copy(vetVisits = updated)
    }

    fun deleteVetVisit(visit: VetVisitRecord) {
        val profile = _dogProfile.value ?: return
        _dogProfile.value = profile.copy(vetVisits = profile.vetVisits.filterNot { it.id == visit.id })
    }

    fun calculateAge(birthDate: Date?): Int {
        birthDate ?: return 0
        val now = Calendar.getInstance()
        val birth = Calendar.getInstance().apply { time = birthDate }

        var age = now.get(Calendar.YEAR) - birth.get(Calendar.YEAR)
        if (now.get(Calendar.DAY_OF_YEAR) < birth.get(Calendar.DAY_OF_YEAR)) age--
        return age.coerceAtLeast(0)
    }

    fun addWeightRecord(record: WeightEntry) {
        val profile = _dogProfile.value ?: return
        _dogProfile.value = profile.copy(
            weight = record.weight,
            weightHistory = profile.weightHistory + record
        )
    }

    // =========================================================
    // GPS (igual)
    // =========================================================
    private val _gpsRoutes = MutableLiveData<List<GpsLocation>>(emptyList())
    val gpsRoutes: LiveData<List<GpsLocation>> = _gpsRoutes

    fun addGpsLocation(lat: Double, lng: Double, date: String, time: String) {
        viewModelScope.launch {
            val location = GpsLocation(
                id = System.currentTimeMillis(),
                latitude = lat,
                longitude = lng,
                timestamp = System.currentTimeMillis(),
                date = date,
                time = time
            )
            val currentList = _gpsRoutes.value?.toMutableList() ?: mutableListOf()
            currentList.add(location)

            val sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000)
            _gpsRoutes.value = currentList.filter { it.timestamp >= sevenDaysAgo }
        }
    }

    fun getRoutesForDay(date: String): List<GpsLocation> =
        _gpsRoutes.value?.filter { it.date == date } ?: emptyList()

    fun getLast7DaysRoutes(): Map<String, List<GpsLocation>> =
        (_gpsRoutes.value ?: emptyList()).groupBy { it.date }

    // =========================================================
    // ✅ RESTAURADAS: vacunas / desparasitación (para PetProfileScreen)
    // =========================================================
    fun addVaccination(vaccination: VaccinationRecord) {
        val currentProfile = _dogProfile.value ?: return
        val currentRecord = currentProfile.medicalRecord
        val updatedVaccinations = currentRecord.vaccinations + vaccination
        val updatedRecord = currentRecord.copy(vaccinations = updatedVaccinations)
        _dogProfile.value = currentProfile.copy(medicalRecord = updatedRecord)

        NotificationHelper.scheduleMedicalReminder(
            context,
            "Vacuna",
            vaccination.name,
            vaccination.nextDueDate.time
        )
        checkMedicalReminders()
    }

    fun updateVaccination(vaccination: VaccinationRecord) {
        val profile = _dogProfile.value ?: return
        val updated = profile.medicalRecord.vaccinations.map { if (it.id == vaccination.id) vaccination else it }
        _dogProfile.value = profile.copy(medicalRecord = profile.medicalRecord.copy(vaccinations = updated))
        checkMedicalReminders()
    }

    fun addDewormingEntry(deworming: Deworming) {
        val currentProfile = _dogProfile.value ?: return
        val currentRecord = currentProfile.medicalRecord
        val updatedDewormings = currentRecord.dewormings + deworming
        val updatedRecord = currentRecord.copy(dewormings = updatedDewormings)
        _dogProfile.value = currentProfile.copy(medicalRecord = updatedRecord)

        NotificationHelper.scheduleMedicalReminder(
            context,
            "Desparasitación",
            deworming.productName,
            deworming.nextDueDate.time
        )
        checkMedicalReminders()
    }

    fun updateDewormingEntry(deworming: Deworming) {
        val profile = _dogProfile.value ?: return
        val updated = profile.medicalRecord.dewormings.map { if (it.id == deworming.id) deworming else it }
        _dogProfile.value = profile.copy(medicalRecord = profile.medicalRecord.copy(dewormings = updated))
        checkMedicalReminders()
    }

    private fun checkMedicalReminders() {
        // (tu implementación original aquí; si la tienes, pégala tal cual)
        // Si no quieres notificaciones por ahora, puedes dejarlo vacío.
    }

    // =========================================================
    // Alertas
    // =========================================================
    private fun checkAlerts(newActivity: DogActivityData) {
        val daily = _dailyStats.value ?: return
        val profile = _dogProfile.value ?: return
        val target = profile.targetDailySteps

        if (daily.totalSteps < target * 0.3) {
            addAlert(
                DogAlert(
                    id = System.currentTimeMillis(),
                    type = AlertType.LOW_ACTIVITY,
                    message = "Actividad baja hoy: solo ${daily.totalSteps} pasos",
                    severity = 2,
                    recommendedAction = "Programar paseo extra de 15 minutos"
                )
            )
        }

        if (daily.totalSteps >= target && daily.goalAchieved) {
            addAlert(
                DogAlert(
                    id = System.currentTimeMillis(),
                    type = AlertType.GOAL_ACHIEVED,
                    message = "🎉 ¡Objetivo diario alcanzado! ${daily.totalSteps} pasos",
                    severity = 1,
                    recommendedAction = "¡Buen trabajo! Mañana seguimos así"
                )
            )
        }

        if (newActivity.activityType == 2 && newActivity.intensity > 0.8f) {
            addAlert(
                DogAlert(
                    id = System.currentTimeMillis(),
                    type = AlertType.OVEREXERTION,
                    message = "Actividad intensa detectada",
                    severity = 2,
                    recommendedAction = "Proporcionar agua y descanso"
                )
            )
        }
    }

    private fun addAlert(alert: DogAlert) {
        val currentAlerts = _alerts.value?.toMutableList() ?: mutableListOf()
        if (!currentAlerts.any { it.type == alert.type && it.message == alert.message }) {
            currentAlerts.add(0, alert)
            _alerts.value = currentAlerts.take(20)
        }
    }

    private fun generateInitialAlerts() {
        _alerts.value = listOf(
            DogAlert(
                id = System.currentTimeMillis(),
                type = AlertType.HEALTH_TIP,
                message = "💡 Recuerda: Los perros necesitan ejercicio diario para su bienestar",
                severity = 1
            ),
            DogAlert(
                id = System.currentTimeMillis() + 1,
                type = AlertType.VET_REMINDER,
                message = "📅 Próxima visita al veterinario recomendada en 2 semanas",
                severity = 2,
                recommendedAction = "Agendar cita"
            ),
            DogAlert(
                id = System.currentTimeMillis() + 2,
                type = AlertType.HEALTH_TIP,
                message = "🐕 ¡Bienvenido a DogFit! Configura el perfil de tu mascota",
                severity = 1
            )
        )
    }

    fun markAlertAsRead(alertId: Long) {
        val currentAlerts = _alerts.value?.toMutableList() ?: return
        val index = currentAlerts.indexOfFirst { it.id == alertId }
        if (index != -1) {
            currentAlerts[index] = currentAlerts[index].copy(isRead = true)
            _alerts.value = currentAlerts
        }
    }

    fun clearAllAlerts() {
        _alerts.value = emptyList()
    }

    // =========================================================
    // Utilidades
    // =========================================================
    private fun calculateDistance(steps: Int, breed: String): Float {
        val stepLength = when (breed) {
            "Labrador", "Golden Retriever", "Pastor Alemán" -> 0.65f
            "Bulldog" -> 0.40f
            "Chihuahua" -> 0.25f
            else -> 0.50f
        }
        return steps * stepLength / 1000f
    }

    private fun calculateWellnessScore(activity: Float, sleep: Float, consistency: Float): Int {
        val score = ((activity.coerceIn(0f, 1.5f) * 0.5f) +
                (sleep.coerceIn(0f, 1f) * 0.3f) +
                (consistency * 0.2f)) * 100f
        return score.toInt().coerceIn(0, 100)
    }

    // Getters usados en MainActivity legacy
    fun getActivityValue(): Int? = _activityValue.value
    fun getBatteryValue(): Int? = _batteryValue.value
}
