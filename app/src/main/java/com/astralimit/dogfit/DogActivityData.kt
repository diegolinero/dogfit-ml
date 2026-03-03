package com.astralimit.dogfit

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "dog_activity")
data class DogActivityData(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val activityType: Int = 0, // 0=caminata, 1=carrera, 2=reposo, 3=juego
    val intensity: Float = 0f, // 0.0 a 1.0
    val steps: Int = 0,
    val estimatedDistance: Float = 0f,
    val durationMinutes: Int = 0,
    val calories: Float = 0f,
    val heartRate: Int? = null,
    val temperature: Float? = null, // temperatura ambiente
    val date: String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        .format(java.util.Date(timestamp))
) {
    fun getActivityTypeString(): String {
        return when(activityType) {
            0 -> "Caminata"
            1 -> "Carrera"
            2 -> "Reposo"
            3 -> "Juego"
            else -> "Desconocido"
        }
    }

    fun getActivityEmoji(): String {
        return when(activityType) {
            0 -> "🚶"
            1 -> "🏃"
            2 -> "🛌"
            3 -> "🎾"
            else -> "❓"
        }
    }
}
