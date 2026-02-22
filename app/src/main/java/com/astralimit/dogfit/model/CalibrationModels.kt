package com.astralimit.dogfit.model

/**
 * Características estadísticas extraídas de una ventana de muestras de aceleración.
 * Estas métricas permiten diferenciar patrones de movimiento más allá del promedio simple.
 */
data class ActivityFeatures(
    val mean: Float,       // Media: nivel base de actividad
    val std: Float,        // Desviación estándar: variabilidad del movimiento
    val variance: Float,   // Varianza: dispersión de la señal (std²)
    val rms: Float,        // RMS: energía efectiva del movimiento
    val peak: Float,       // Pico máximo: intensidad máxima en la ventana
    val energy: Float      // Energía total: suma de cuadrados normalizada
) {
    companion object {
        val EMPTY = ActivityFeatures(0f, 0f, 0f, 0f, 0f, 0f)
    }
}

/**
 * Datos de calibración para una actividad específica.
 * Almacena todas las métricas estadísticas necesarias para la clasificación.
 */
data class CalibrationFeatures(
    val mean: Float = 0f,
    val std: Float = 0f,
    val variance: Float = 0f,
    val rms: Float = 0f,
    val peak: Float = 0f,
    val energy: Float = 0f
) {
    fun toFloatArray(): FloatArray = floatArrayOf(mean, std, variance, rms, peak, energy)

    companion object {
        fun fromFloatArray(arr: FloatArray): CalibrationFeatures {
            return if (arr.size >= 6) {
                CalibrationFeatures(arr[0], arr[1], arr[2], arr[3], arr[4], arr[5])
            } else {
                CalibrationFeatures()
            }
        }

        val EMPTY = CalibrationFeatures()
    }
}
