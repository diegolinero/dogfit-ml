package com.astralimit.dogfit

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class EdgeImpulseUploader(
    private val ingestionUrl: String = DEFAULT_INGESTION_URL
) {
    companion object {
        const val DEFAULT_INGESTION_URL = "https://ingestion.edgeimpulse.com/api/training/data"
    }

    fun uploadCsvFile(file: File, apiKey: String, deviceName: String): Pair<Boolean, String> {
        val csvData = parseCsvForEdge(file)
        if (csvData.isEmpty()) return false to "${file.name}: sin muestras válidas"

        val label = extractLabelFromFileName(file)
        val payload = buildLegacyTrainingPayload(
            deviceName = deviceName,
            deviceType = "dogfit-collar",
            intervalMs = estimateIntervalMs(file),
            values = csvData
        )

        val bodyBytes = payload.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(ingestionUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("x-api-key", apiKey)
            setRequestProperty("x-file-name", file.name)
            setRequestProperty("x-label", label)
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(bodyBytes.size)
        }

        return try {
            connection.outputStream.use { output ->
                output.write(bodyBytes)
            }
            val responseCode = connection.responseCode
            val success = responseCode in 200..299
            if (success) {
                true to ""
            } else {
                val errorBody = runCatching {
                    connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                }.getOrElse { "" }
                false to "HTTP $responseCode ${connection.responseMessage.orEmpty()} ${errorBody.take(180)}".trim()
            }
        } catch (e: Exception) {
            false to (e.message ?: e::class.java.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseCsvForEdge(file: File): List<List<Double>> {
        val rows = mutableListOf<List<Double>>()
        file.useLines { lines ->
            lines.drop(1).forEach { line ->
                val parts = line.split(',')
                if (parts.size < 7) return@forEach
                val values = parts.drop(1).take(6).mapNotNull { it.toDoubleOrNull() }
                if (values.size == 6) rows += values
            }
        }
        return rows
    }

    private fun estimateIntervalMs(file: File): Double {
        val timestamps = mutableListOf<Long>()
        file.useLines { lines ->
            lines.drop(1).forEach { line ->
                val ts = line.substringBefore(',').toLongOrNull() ?: return@forEach
                timestamps += ts
            }
        }
        if (timestamps.size < 2) return 100.0
        val deltas = timestamps.zipWithNext { a, b -> (b - a).coerceAtLeast(0L) }.filter { it > 0L }
        if (deltas.isEmpty()) return 100.0
        return deltas.average().coerceAtLeast(1.0)
    }

    private fun extractLabelFromFileName(file: File): String {
        val name = file.nameWithoutExtension
        val parts = name.split('_')
        return parts.firstOrNull().orEmpty().ifBlank { "capture" }
    }

    private fun buildLegacyTrainingPayload(
        deviceName: String,
        deviceType: String,
        intervalMs: Double,
        values: List<List<Double>>
    ): JSONObject {
        val sensors = JSONArray().apply {
            put(JSONObject().put("name", "ax").put("units", "g"))
            put(JSONObject().put("name", "ay").put("units", "g"))
            put(JSONObject().put("name", "az").put("units", "g"))
            put(JSONObject().put("name", "gx").put("units", "dps"))
            put(JSONObject().put("name", "gy").put("units", "dps"))
            put(JSONObject().put("name", "gz").put("units", "dps"))
        }

        val valuesArray = JSONArray()
        values.forEach { row ->
            val rowArray = JSONArray()
            row.forEach { rowArray.put(it) }
            valuesArray.put(rowArray)
        }

        val payload = JSONObject().apply {
            put("device_name", deviceName)
            put("device_type", deviceType)
            put("interval_ms", intervalMs)
            put("sensors", sensors)
            put("values", valuesArray)
        }

        return JSONObject().apply {
            put("payload", payload)
            put("protected", JSONObject().put("alg", "none").put("ver", "v1"))
            put("signature", "00")
        }
    }
}
