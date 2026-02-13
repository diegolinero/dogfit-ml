package com.astralimit.dogfit

import java.util.Calendar
import java.util.Date

data class DogProfile(
    val id: Long = System.currentTimeMillis(),
    val name: String = "Max",
    val petType: PetType = PetType.DOG,
    val breed: String = "Mixed",
    val birthDate: Date? = Calendar.getInstance().apply { add(Calendar.YEAR, -3) }.time,
    val age: Int = 3,
    val weight: Float = 15f,
    val weightHistory: List<WeightEntry> = listOf(WeightEntry(System.currentTimeMillis(), 15f)),
    val height: Float = 50f,
    val dailyCalories: Int = 800,
    val imageUrl: String = "",
    val color: String = "",
    val microchipNumber: String = "",
    val medicalRecord: PetMedicalRecord = PetMedicalRecord(),
    val vetVisits: List<VetVisitRecord> = emptyList(),
    val targetActiveMinutes: Int = 60,
    val targetDailySteps: Int = 5000,
    val activitySensitivity: Float = 1.0f,
    val isCalibrated: Boolean = false,
    val calibrationRestValue: Float = 0.10f,
    val calibrationWalkValue: Float = 0.30f,
    val calibrationRunValue: Float = 0.80f,
    val calibrationRestFeatures: CalibrationFeatures = CalibrationFeatures(),
    val calibrationWalkFeatures: CalibrationFeatures = CalibrationFeatures(),
    val calibrationRunFeatures: CalibrationFeatures = CalibrationFeatures()
)

typealias VetVisitRecord = com.astralimit.dogfit.model.VetVisitRecord
typealias VetVisit = com.astralimit.dogfit.model.VetVisitRecord

typealias WeightEntry = com.astralimit.dogfit.model.WeightEntry
typealias WeightRecord = com.astralimit.dogfit.model.WeightEntry
