package com.astralimit.dogfit

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

data class CapturedSample(
    val timestamp: Long,
    val ax: Int,
    val ay: Int,
    val az: Int,
    val gx: Int,
    val gy: Int,
    val gz: Int
)

data class CapturedSession(
    val file: File,
    val activityLabel: String,
    val customLabel: String,
    val fullLabel: String
)

class DataCaptureManager(private val context: Context) {
    private var labelName: String = "unlabeled"
    private var labelId: Int = 0
    private var customLabel: String = ""
    private val samples = mutableListOf<CapturedSample>()
    private var captureStartedAt = 0L
    private var isCapturing = false

    private val captureDir: File by lazy {
        File(context.filesDir, "captures").apply { mkdirs() }
    }

    fun setActivityLabel(label: String, labelId: Int) {
        this.labelName = label.lowercase().replace(" ", "_")
        this.labelId = labelId
    }

    fun setCustomLabel(customLabel: String) {
        this.customLabel = sanitizeForFileName(customLabel)
    }

    fun startCapture() {
        captureStartedAt = System.currentTimeMillis()
        samples.clear()
        isCapturing = true
    }

    fun addRawSample(data: ByteArray) {
        if (!isCapturing) return
        val sample = BlePacketParser.parseCapture(data).firstOrNull() ?: return
        samples += CapturedSample(
            timestamp = System.currentTimeMillis(),
            ax = sample.ax.toInt(),
            ay = sample.ay.toInt(),
            az = sample.az.toInt(),
            gx = sample.gx.toInt(),
            gy = sample.gy.toInt(),
            gz = sample.gz.toInt()
        )
    }

    fun stopCapture(): File? {
        if (!isCapturing) return null
        isCapturing = false
        if (samples.isEmpty()) return null

        val suffix = customLabel.ifBlank { "sin_detalle" }
        val fileName = "${labelName}_${suffix}_${captureStartedAt}.csv"
        val outFile = File(captureDir, fileName)
        outFile.bufferedWriter().use { writer ->
            writer.appendLine("timestamp,ax,ay,az,gx,gy,gz")
            samples.forEach {
                writer.appendLine("${it.timestamp},${it.ax},${it.ay},${it.az},${it.gx},${it.gy},${it.gz}")
            }
        }
        return outFile
    }

    fun listCapturedFiles(): List<File> {
        return captureDir.listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun listCapturedSessions(): List<CapturedSession> {
        return listCapturedFiles().map { file ->
            val name = file.nameWithoutExtension
            val parts = name.split("_")
            val timestampIndex = parts.indexOfLast { it.toLongOrNull() != null }
            val activity = parts.firstOrNull().orEmpty().ifBlank { "sin_actividad" }
            val custom = if (timestampIndex > 1) {
                parts.subList(1, timestampIndex).joinToString("_")
            } else {
                parts.getOrNull(1).orEmpty()
            }.ifBlank { "sin_detalle" }
            CapturedSession(
                file = file,
                activityLabel = activity,
                customLabel = custom,
                fullLabel = "${activity}_${custom}"
            )
        }
    }

    fun buildShareIntent(file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun buildShareAllIntent(): Intent? {
        val uris = listCapturedFiles().map {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
        }
        if (uris.isEmpty()) return null
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/csv"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun isCapturing(): Boolean = isCapturing
    fun getLabelId(): Int = labelId

    private fun sanitizeForFileName(raw: String): String {
        val cleaned = raw
            .lowercase()
            .trim()
            .replace(" ", "_")
            .replace(Regex("[^a-z0-9_-]"), "")
            .replace(Regex("_+"), "_")
            .trim('_')
        return cleaned.take(48)
    }
}
