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

typealias VaccinationRecord = com.astralimit.dogfit.model.VaccinationRecord
typealias Vaccination = com.astralimit.dogfit.model.VaccinationRecord
typealias Deworming = com.astralimit.dogfit.model.Deworming

typealias PetMedicalRecordModel = com.astralimit.dogfit.model.PetMedicalRecordModel
typealias PetMedicalRecord = com.astralimit.dogfit.model.PetMedicalRecordModel

typealias MedicalReminderModel = com.astralimit.dogfit.model.MedicalReminderModel
typealias MedicalReminder = com.astralimit.dogfit.model.MedicalReminderModel

typealias ReminderKind = com.astralimit.dogfit.model.ReminderKind
typealias ReminderType = com.astralimit.dogfit.model.ReminderKind

typealias PetKind = com.astralimit.dogfit.model.PetKind
typealias PetType = com.astralimit.dogfit.model.PetKind
