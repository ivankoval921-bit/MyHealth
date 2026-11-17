package com.myhealth.ai.model

enum class DataSourceTrust(val badgeRu: String, val badgeDe: String) {
    USER_INPUT("Пользователь", "Nutzereingabe"),
    CLINICIAN_OR_LAB("Врач / Лаб.", "Arzt / Labor"),
    VALIDATED_DEVICE("Мед. прибор", "Valid. Gerät"),
    WEARABLE_SENSOR("Часы (ориентир)", "Wearable (Orientierung)"),
    EXTERNAL_ENV("Среда (Open-Meteo)", "Umwelt (Open-Meteo)"),
    AI_HYPOTHESIS("ИИ-оценка", "KI-Schätzung")
}

enum class HealthCategory(val titleRu: String, val titleDe: String) {
    SYMPTOM("Симптом", "Symptom"),
    VITALS("Показатель", "Messwert"),
    FOOD_DRINK("Питание / Напитки", "Ernährung / Getränke"),
    HABIT_MEDS("Привычки / Лекарства", "Gewohnheiten / Medikamente"),
    GI_DIGESTION("ЖКТ / Стул / Изжога", "Verdauung / Reflux"),
    DOCUMENT_LAB("Документ / Анализ", "Befund / Labor"),
    SLEEP_ACTIVITY("Сон / Активность", "Schlaf / Aktivität"),
    NOTE("Заметка", "Notiz")
}

enum class VitalType(val titleRu: String, val titleDe: String, val defaultUnit: String) {
    BLOOD_PRESSURE("Артериальное давление", "Blutdruck", "мм рт.ст."),
    HEART_RATE("Пульс", "Puls", "уд/мин"),
    SLEEP_HOURS("Сон", "Schlaf", "ч"),
    STEPS("Шаги", "Schritte", "шагов"),
    WEIGHT_KG("Вес", "Gewicht", "кг"),
    TEMPERATURE_C("Температура", "Temperatur", "°C"),
    GLUCOSE_MGDL("Глюкоза крови", "Blutzucker", "мг/дл"),
    SPO2_PCT("Кислород SpO₂", "SpO₂", "%"),
    URINE_PH("pH мочи (тест-полоска)", "Urin-pH (Teststreifen)", "pH мочи")
}

enum class IntakeKind(val titleRu: String) {
    MEAL("Еда / блюдо"),
    COFFEE("Кофе"),
    TEA("Чай"),
    WATER("Вода"),
    ALCOHOL("Алкоголь"),
    CAFFEINE_PILL("Таблетка кофеина / энергетик"),
    MEDICATION("Лекарство / витамин"),
    SMOKING("Курение")
}

enum class DocType(val titleRu: String, val titleDe: String) {
    LAB_RESULT("Анализ крови / мочи", "Laborbefund"),
    DOCTOR_NOTE("Заключение врача / выписка", "Arztbrief / Befund"),
    PRESCRIPTION("Рецепт / назначения", "Rezept / Medikation"),
    IMAGING_MRI_CT_ECG("МРТ / КТ / УЗИ / ЭКГ", "MRT / CT / Sonographie / EKG"),
    DEVICE_PHOTO("Фото экрана прибора / полоски", "Gerätefoto / Teststreifen"),
    EYE_OR_SKIN_PHOTO("Фото глаз / кожи (динамика)", "Augen-/Hautfoto (Verlauf)"),
    EPA_EXPORT("Импорт ePA / медкарта", "ePA-Export / Patientenakte")
}

enum class LabFlag(val titleRu: String) {
    LOW("Ниже нормы"),
    NORMAL("В норме"),
    HIGH("Выше нормы"),
    UNKNOWN("Нет референса")
}

enum class RecommendationTier(val titleRu: String, val titleDe: String) {
    OBSERVATION("1. Наблюдение", "1. Beobachtung"),
    LIFESTYLE("2. Образ жизни", "2. Lebensstil"),
    SCHEDULE_DOCTOR("3. Повод к врачу", "3. Arzttermin empfohlen"),
    URGENT("4. СРОЧНАЯ ПОМОЩЬ", "4. NOTFALL / SOFORT")
}

enum class ReportLanguage(val code: String, val titleRu: String) {
    RU("RU", "Русский"),
    DE("DE", "Deutsch")
}

data class HealthProfile(
    val id: String,
    val name: String,
    val birthYear: Int? = null,
    val sex: String = "",
    val homeAddress: String = "",
    val chronicConditions: String = "",
    val medications: String = "",
    val allergies: String = "",
    val emergencyContactName: String = "",
    val emergencyContactPhone: String = "",
    val autoSmsCheckInEnabled: Boolean = false,
    val preferredReportLang: ReportLanguage = ReportLanguage.RU,
    val createdAtMillis: Long = System.currentTimeMillis()
)

data class HealthEvent(
    val id: String,
    val profileId: String,
    val timestampMillis: Long,
    val category: HealthCategory,
    val title: String,
    val notes: String = "",
    val severity0to10: Int? = null,
    val bodyRegion: String = "",
    val painCharacter: String = "",
    val startedAtMillis: Long? = null,
    val tags: List<String> = emptyList(),
    val sourceTrust: DataSourceTrust = DataSourceTrust.USER_INPUT,
    val sourceDetail: String = "",
    val weatherSnapshotId: String? = null,
    val attachmentId: String? = null,
    val isRedFlagTriggered: Boolean = false,
    val clarifyingQaSummary: String = ""
)

data class VitalMeasurement(
    val id: String,
    val profileId: String,
    val measuredAtMillis: Long,
    val type: VitalType,
    val value1: Double,
    val value2: Double? = null,
    val unit: String = type.defaultUnit,
    val contextTag: String = "",
    val sourceTrust: DataSourceTrust = DataSourceTrust.USER_INPUT,
    val sourceDetail: String = "",
    val attachmentPath: String? = null
)

data class IntakeEntry(
    val id: String,
    val profileId: String,
    val timestampMillis: Long,
    val kind: IntakeKind,
    val name: String,
    val amountValue: Double? = null,
    val amountUnit: String = "",
    val estimatedKcal: Int? = null,
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val carbsG: Double? = null,
    val photoPath: String? = null,
    val sourceTrust: DataSourceTrust = DataSourceTrust.USER_INPUT,
    val notes: String = ""
)

data class WeatherSnapshot(
    val id: String,
    val timestampMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val locationName: String,
    val temperatureC: Double,
    val humidityPct: Int,
    val pressureHpa: Double,
    val pressureMmHg: Double,
    val pressureDelta6hHpa: Double,
    val pressureDelta24hHpa: Double,
    val aqiEuropean: Int? = null,
    val pm25: Double? = null,
    val kpIndex: Double? = null,
    val summaryText: String
)

data class MedicalDocument(
    val id: String,
    val profileId: String,
    val documentDateMillis: Long,
    val createdAtMillis: Long,
    val docType: DocType,
    val title: String,
    val doctorOrClinic: String = "",
    val originalFilePath: String,
    val mimeType: String,
    val ocrText: String = "",
    val extractedSummary: String = "",
    val userCorrected: Boolean = false,
    val sourceTrust: DataSourceTrust = DataSourceTrust.CLINICIAN_OR_LAB
)

data class LabMarker(
    val id: String,
    val documentId: String,
    val profileId: String,
    val measuredAtMillis: Long,
    val markerName: String,
    val value: Double,
    val unit: String,
    val refMin: Double? = null,
    val refMax: Double? = null,
    val flag: LabFlag = LabFlag.UNKNOWN,
    val userVerified: Boolean = false
)

data class AuditLogEntry(
    val id: String,
    val timestampMillis: Long,
    val profileId: String,
    val action: String,
    val details: String
)

data class ClarifyingQuestion(
    val key: String,
    val promptRu: String,
    val quickOptions: List<String>
)

data class ActionSuggestion(
    val key: String,
    val titleRu: String,
    val reasonRu: String
)

data class RedFlagAssessment(
    val isUrgent: Boolean,
    val tier: RecommendationTier,
    val headline: String,
    val reason: String,
    val actions: List<String>,
    val callEmergencyNumber: String = "112",
    val recommendNotifyContact: Boolean = false,
    val isChronicContextNote: Boolean = false
)

data class CorrelationInsight(
    val id: String,
    val symptomLabel: String,
    val factorLabel: String,
    val windowHours: Int,
    val coOccurrencePct: Int,
    val matchedCount: Int,
    val totalSymptomCount: Int,
    val baselineFactorPct: Int,
    val confidenceLabel: String,
    val tier: RecommendationTier,
    val summaryText: String,
    val alternativeExplanations: String
)

data class LabTrendSummary(
    val markerName: String,
    val latestValue: Double,
    val previousValue: Double?,
    val unit: String,
    val latestDateMillis: Long,
    val flag: LabFlag,
    val deltaText: String,
    val count: Int
)

data class AiSettings(
    val aiConsentGranted: Boolean = false,
    val geminiApiKey: String = "",
    val geminiModel: String = "gemini-3.8-flash",
    val speechLangTag: String = "ru-RU",
    val autoWeatherEnabled: Boolean = true,
    val includeGeomagneticRef: Boolean = false
) {
    val isGeminiReady: Boolean
        get() = aiConsentGranted && geminiApiKey.isNotBlank()
}
