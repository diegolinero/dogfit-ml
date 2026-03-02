package com.astralimit.dogfit

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode

class QrScannerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_QR_DATA = "extra_qr_data"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()

        val scanner: GmsBarcodeScanner = GmsBarcodeScanning.getClient(this, options)
        startScan(scanner)
    }

    private fun startScan(scanner: GmsBarcodeScanner) {
        val task: Task<Barcode> = scanner.startScan()
        task.addOnSuccessListener { barcode ->
            val raw = barcode.rawValue.orEmpty()
            if (raw.isBlank()) {
                Toast.makeText(this, "QR vacío", Toast.LENGTH_SHORT).show()
                setResult(Activity.RESULT_CANCELED)
            } else {
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_QR_DATA, raw))
            }
            finish()
        }.addOnCanceledListener {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }.addOnFailureListener {
            Toast.makeText(this, "No se pudo escanear QR", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }
}
