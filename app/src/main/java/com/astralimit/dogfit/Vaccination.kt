package com.astralimit.dogfit

data class GpsLocation(
    val id: Long = System.currentTimeMillis(),
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val date: String,
    val time: String,
    val accuracy: Float = 0f
)

data class RouteDay(
    val date: String,
    val locations: List<GpsLocation>,
    val totalDistanceKm: Float,
    val durationMinutes: Int
)

typealias Vaccination = com.astralimit.dogfit.model.VaccinationRecord
typealias DewormingEntry = com.astralimit.dogfit.model.Deworming

typealias PetMedicalRecord = com.astralimit.dogfit.model.PetMedicalRecordModel

typealias MedicalReminder = com.astralimit.dogfit.model.MedicalReminderModel

typealias ReminderType = com.astralimit.dogfit.model.ReminderKind

typealias PetType = com.astralimit.dogfit.model.PetKind
