package com.astralimit.dogfit.model

import java.util.Date

data class VetVisitRecord(
    val id: Long = System.currentTimeMillis(),
    val date: Date = Date(),
    val reason: String,
    val clinicName: String = "",
    val prescriptionImageUrl: String = "",
    val notes: String = ""
)

data class WeightEntry(
    val timestamp: Long,
    val weight: Float
)
