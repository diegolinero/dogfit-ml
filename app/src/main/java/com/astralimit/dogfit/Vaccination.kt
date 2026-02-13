package com.astralimit.dogfit

import java.util.Date

data class VaccinationRecord(
    val id: Long = System.currentTimeMillis(),
    val name: String,
    val applicationDate: Date,
    val nextDueDate: Date,
    val veterinarianName: String = "",
    val clinic: String = "",
    val batchNumber: String = "",
    val notes: String = ""
)

data class Deworming(
    val id: Long = System.currentTimeMillis(),
    val productName: String,
    val applicationDate: Date,
    val nextDueDate: Date,
    val dosage: String = "",
    val weight: Float = 0f,
    val veterinarianName: String = "",
    val notes: String = ""
)

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

data class PetMedicalRecordModel(
    val vaccinations: List<VaccinationRecord> = emptyList(),
    val dewormings: List<Deworming> = emptyList(),
    val upcomingReminders: List<MedicalReminderModel> = emptyList()
)

data class MedicalReminderModel(
    val id: Long = System.currentTimeMillis(),
    val type: ReminderKind,
    val title: String,
    val description: String,
    val dueDate: Date,
    val isWeekWarning: Boolean = false,
    val isDayWarning: Boolean = false
)

enum class ReminderKind {
    VACCINATION,
    DEWORMING
}

enum class PetKind {
    DOG,
    CAT
}


typealias Vaccination = VaccinationRecord
typealias PetMedicalRecord = PetMedicalRecordModel
typealias MedicalReminder = MedicalReminderModel
typealias ReminderType = ReminderKind
typealias PetType = PetKind
