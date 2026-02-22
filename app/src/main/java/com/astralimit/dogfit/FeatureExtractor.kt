package com.astralimit.dogfit

import com.astralimit.dogfit.model.ActivityFeatures
import com.astralimit.dogfit.model.CalibrationFeatures
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Extractor de características estadísticas de señales de aceleración.
 * Calcula métricas que caracterizan el patrón de movimiento en una ventana temporal.
 */
object FeatureExtractor {

    /**
     * Extrae todas las características estadísticas de una lista de muestras.
     * @param samples Lista de magnitudes de aceleración (ya filtradas)
     * @return ActivityFeatures con todas las métricas calculadas
     */
    fun extract(samples: List<Float>): ActivityFeatures {
        if (samples.isEmpty()) return ActivityFeatures.EMPTY

        val n = samples.size
        val mean = samples.average().toFloat()

        var sumSqDiff = 0f
        var sumSq = 0f
        var maxVal = 0f

        for (s in samples) {
            sumSqDiff += (s - mean).pow(2)
            sumSq += s.pow(2)
            if (kotlin.math.abs(s) > maxVal) maxVal = kotlin.math.abs(s)
        }

        val variance = sumSqDiff / n
        val std = sqrt(variance)
        val rms = sqrt(sumSq / n)
        val energy = sumSq / n

        return ActivityFeatures(mean, std, variance, rms, maxVal, energy)
    }

    /**
     * Calcula la distancia euclidiana entre dos conjuntos de características.
     * Útil para comparar ventanas actuales con patrones de calibración.
     */
    fun distance(current: ActivityFeatures, reference: CalibrationFeatures): Float {
        val dMean = current.mean - reference.mean
        val dStd = current.std - reference.std
        val dRms = current.rms - reference.rms
        val dEnergy = current.energy - reference.energy

        return sqrt(dMean * dMean + dStd * dStd + dRms * dRms + dEnergy * dEnergy)
    }
}
