package com.myhealth.ai

import com.myhealth.ai.data.HealthPrefs
import com.myhealth.ai.logic.CorrelationEngine
import com.myhealth.ai.logic.GeminiHealthClient
import com.myhealth.ai.logic.MedicalDocumentEngine
import com.myhealth.ai.logic.RedFlagSafetyEngine
import com.myhealth.ai.logic.SymptomAndClarifierEngine
import com.myhealth.ai.logic.UpdateChecker
import com.myhealth.ai.logic.WeatherClient
import com.myhealth.ai.model.DataSourceTrust
import com.myhealth.ai.model.HealthCategory
import com.myhealth.ai.model.HealthEvent
import com.myhealth.ai.model.HealthProfile
import com.myhealth.ai.model.IntakeEntry
import com.myhealth.ai.model.IntakeKind
import com.myhealth.ai.model.LabFlag
import com.myhealth.ai.model.LabMarker
import com.myhealth.ai.model.RecommendationTier
import com.myhealth.ai.model.ReportLanguage
import com.myhealth.ai.model.VitalMeasurement
import com.myhealth.ai.model.VitalType
import com.myhealth.ai.model.WeatherSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthEnginesTest {

    @Test
    fun redFlagDetectsCardiacChestPainImmediately() {
        val res = RedFlagSafetyEngine.evaluate("Сильно давит в груди и тяжело дышать, отдает в левую руку")
        assertTrue(res.isUrgent)
        assertEquals(RecommendationTier.URGENT, res.tier)
        assertEquals("112", res.callEmergencyNumber)
    }

    @Test
    fun redFlagDetectsCriticalBloodPressure() {
        val bp = VitalMeasurement(
            id = "bp1",
            profileId = "p1",
            measuredAtMillis = 1_000_000L,
            type = VitalType.BLOOD_PRESSURE,
            value1 = 192.0,
            value2 = 114.0
        )
        val res = RedFlagSafetyEngine.evaluate("Болит голова", severity0to10 = 6, latestVitals = listOf(bp))
        assertTrue(res.isUrgent)
        assertEquals(RecommendationTier.URGENT, res.tier)
    }

    @Test
    fun redFlagStaysCalmForMildHeadache() {
        val res = RedFlagSafetyEngine.evaluate("Немного болит голова после работы", severity0to10 = 3)
        assertFalse(res.isUrgent)
        assertEquals(RecommendationTier.OBSERVATION, res.tier)
    }

    @Test
    fun offlineParserExtractsVitalsIntakesAndMissingQuestions() {
        val parsed = SymptomAndClarifierEngine.parseOffline(
            rawText = "Болит голова, давление 145/95, пульс 88, спал 5.5 ч, выпил 2 кружки кофе",
            profileId = "p1",
            nowMillis = 1_700_000_000_000L
        )
        assertTrue(parsed.vitals.any { it.type == VitalType.BLOOD_PRESSURE && it.value1 == 145.0 && it.value2 == 95.0 })
        assertTrue(parsed.vitals.any { it.type == VitalType.HEART_RATE && it.value1 == 88.0 })
        assertTrue(parsed.vitals.any { it.type == VitalType.SLEEP_HOURS && it.value1 == 5.5 })
        assertTrue(parsed.intakes.any { it.kind == IntakeKind.COFFEE && it.amountValue == 2.0 })
        assertTrue(parsed.events.any { it.category == HealthCategory.SYMPTOM })
        // Since onset, severity and pain character are missing, clarifying questions should include them, but NOT triggers (coffee/sleep already given).
        assertTrue(parsed.missingQuestions.any { it.key == "onset" })
        assertTrue(parsed.missingQuestions.any { it.key == "severity" })
        assertFalse(parsed.missingQuestions.any { it.key == "triggers" })
    }

    @Test
    fun weatherParserComputesPressureDeltaAndMmHg() {
        val hours = (0 until 30).map { i ->
            val day = if (i < 24) 16 else 17
            "\"2026-11-${day}T${String.format("%02d", i % 24)}:00\""
        }
        val pressures = (0 until 30).map { i -> if (i < 24) 1020.0 else 1012.0 }
        val sampleJson = """
            {
              "current": {
                "time": "2026-11-17T00:15",
                "temperature_2m": 8.5,
                "relative_humidity_2m": 82,
                "pressure_msl": 1012.0
              },
              "hourly": {
                "time": [${hours.joinToString(",")}],
                "pressure_msl": [${pressures.joinToString(",")}]
              }
            }
        """.trimIndent()
        val snap = WeatherClient.parseOpenMeteoJson(sampleJson, 51.25, 7.15, "Wuppertal", aqiEuropean = 24)
        assertEquals(8.5, snap.temperatureC, 0.01)
        assertEquals(82, snap.humidityPct)
        assertTrue(snap.pressureMmHg in 758.0..760.0)
        assertEquals(-8.0, snap.pressureDelta24hHpa, 0.1)
    }

    @Test
    fun correlationEngineFindsSleepAndPressurePatternsWithHonestConfidence() {
        val base = 1_700_000_000_000L
        val hour = 3_600_000L
        val w1 = WeatherSnapshot("w1", base, 51.0, 7.0, "Home", 10.0, 80, 1005.0, 753.8, -4.5, -7.2, summaryText = "")
        val w2 = WeatherSnapshot("w2", base + 48 * hour, 51.0, 7.0, "Home", 11.0, 78, 1004.0, 753.0, -3.8, -6.5, summaryText = "")

        val events = listOf(
            HealthEvent("e1", "p1", base, HealthCategory.SYMPTOM, "Болит голова / мигрень", bodyRegion = "Голова", severity0to10 = 7, weatherSnapshotId = "w1"),
            HealthEvent("e2", "p1", base + 48 * hour, HealthCategory.SYMPTOM, "Мигрень в висках", bodyRegion = "Виски", severity0to10 = 6, weatherSnapshotId = "w2")
        )
        val vitals = listOf(
            VitalMeasurement("s1", "p1", base - 4 * hour, VitalType.SLEEP_HOURS, 5.0),
            VitalMeasurement("s2", "p1", base + 44 * hour, VitalType.SLEEP_HOURS, 5.2)
        )
        val insights = CorrelationEngine.analyzePatterns(events, vitals, emptyList(), listOf(w1, w2))
        assertTrue(insights.any { it.id == "headache_sleep" && it.coOccurrencePct == 100 })
        assertTrue(insights.any { it.id == "headache_pressure" && it.coOccurrencePct == 100 })
        assertTrue(insights.first().confidenceLabel.contains("мало данных"))
    }

    @Test
    fun geminiInteractionsResponseAndStructuredJsonParseCleanly() {
        val interactionsResp = """
            {
              "id": "v1_test",
              "model": "gemini-3.8-flash",
              "status": "completed",
              "steps": [
                {
                  "type": "model_output",
                  "content": [
                    {
                      "type": "text",
                      "text": "```json\n{\"category\":\"SYMPTOM\",\"title\":\"Головная боль в висках\",\"bodyRegion\":\"Виски\",\"painCharacter\":\"Пульсирующая\",\"severity0to10\":7,\"tags\":[\"Мигрень\",\"Недосып\"],\"followUpQuestions\":[{\"key\":\"onset\",\"promptRu\":\"Когда началась боль?\",\"quickOptions\":[\"Утром\",\"Час назад\"]}],\"guidanceText\":\"Наблюдайте динамику давления.\"}\n```"
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val extractedText = GeminiHealthClient.extractInteractionText(interactionsResp)
        val parsed = GeminiHealthClient.parseSymptomJson(extractedText, "болит голова", "p1", null, "gemini-3.8-flash", 1_000L)
        assertNotNull(parsed)
        assertEquals("Головная боль в висках", parsed!!.events.first().title)
        assertEquals(7, parsed.events.first().severity0to10)
        assertEquals(1, parsed.followUpQuestions.size)
        assertEquals("gemini-3.8-flash", HealthPrefs.sanitizeModel("gemini-2.0-flash-deprecated"))
    }

    @Test
    fun offlineLabParserExtractsGermanAndRussianMarkersAndComputesTrends() {
        val ocr = """
            Laborbericht 12.10.2026
            HbA1c 6,1 %
            CRP 2,4 mg/l
            TSH 2.15 mU/l
            Cholesterin 215 mg/dl
            Vitamin D 24,5 ng/ml
        """.trimIndent()
        val markers = MedicalDocumentEngine.extractCommonLabMarkersOffline(ocr, "doc1", "p1", 1_700_000_000_000L)
        assertTrue(markers.any { it.markerName == "HbA1c" && it.value == 6.1 && it.flag == LabFlag.HIGH })
        assertTrue(markers.any { it.markerName == "CRP / СРБ" && it.value == 2.4 && it.flag == LabFlag.NORMAL })
        assertTrue(markers.any { it.markerName == "Витамин D (25-OH)" && it.value == 24.5 && it.flag == LabFlag.LOW })

        val older = LabMarker("m0", "doc0", "p1", 1_690_000_000_000L, "HbA1c", 6.4, "%", 4.0, 5.7, LabFlag.HIGH)
        val trends = CorrelationEngine.summarizeLabTrends(markers + older)
        val hba = trends.first { it.markerName == "HbA1c" }
        assertEquals(2, hba.count)
        assertTrue(hba.deltaText.startsWith("-0.30"))
    }

    @Test
    fun redFlagDetectsThroatAndJawAnginaEquivalent() {
        val res = RedFlagSafetyEngine.evaluate(
            text = "Ниже горла что-то давит, тяжеловато дышать и душит под нижней челюстью"
        )
        assertTrue(res.isUrgent)
        assertEquals(RecommendationTier.URGENT, res.tier)
        assertEquals("112", res.callEmergencyNumber)
    }

    @Test
    fun redFlagDifferentiatesChronicPinkyNumbnessFromAcuteStroke() {
        val now = 1_700_500_000_000L
        val twoDaysMs = 48 * 3_600_000L
        val prior = listOf(
            HealthEvent("e1", "p1", now - twoDaysMs, HealthCategory.SYMPTOM, "Холодный мизинец на левой руке", bodyRegion = "Левая рука"),
            HealthEvent("e2", "p1", now - twoDaysMs * 2, HealthCategory.SYMPTOM, "Плохая чувствительность на пальцах левой руки", bodyRegion = "Левая рука")
        )
        // Recurring chronic mild finger numbness -> systemic non-emergency chronic note!
        val chronicRes = RedFlagSafetyEngine.evaluate(
            text = "Опять холодный мизинец на левой руке и плохая чувствительность на пальцах",
            priorEvents = prior,
            nowMillis = now
        )
        assertFalse(chronicRes.isUrgent)
        assertTrue(chronicRes.isChronicContextNote)

        // Sudden acute worsening despite chronic history -> urgent!
        val acuteRes = RedFlagSafetyEngine.evaluate(
            text = "Резко онемела вся левая рука, стало резко хуже, не могу двигать",
            priorEvents = prior,
            nowMillis = now
        )
        assertTrue(acuteRes.isUrgent)
    }

    @Test
    fun offlineParserSuggestsBpAndTonometerPhotoWhenMissingVitals() {
        val now = 1_700_000_000_000L
        val parsed = SymptomAndClarifierEngine.parseOffline(
            rawText = "Болит голова и слегка тошнит",
            profileId = "p1",
            recentVitals = emptyList(),
            nowMillis = now
        )
        assertTrue(parsed.actionSuggestions.any { it.key == "measure_bp" })
        assertTrue(parsed.actionSuggestions.any { it.key == "photo_tonometer" })
    }

    @Test
    fun extractBpAndPulseParsesTonometerLcdFormats() {
        val labelOcr = SymptomAndClarifierEngine.extractBpAndPulseFromText(
            rawText = "SYS 142 mmHg DIA 89 PUL 74",
            profileId = "p1",
            trust = DataSourceTrust.VALIDATED_DEVICE,
            sourceDetail = "OCR",
            attachmentPath = "/tmp/tonometer.jpg"
        )
        assertTrue(labelOcr.any { it.type == VitalType.BLOOD_PRESSURE && it.value1 == 142.0 && it.value2 == 89.0 })
        assertTrue(labelOcr.any { it.type == VitalType.HEART_RATE && it.value1 == 74.0 })

        val stackedOcr = SymptomAndClarifierEngine.extractBpAndPulseFromText(
            rawText = "138\n86\n72",
            profileId = "p1",
            trust = DataSourceTrust.VALIDATED_DEVICE,
            sourceDetail = "OCR",
            attachmentPath = "/tmp/tonometer2.jpg"
        )
        assertTrue(stackedOcr.any { it.type == VitalType.BLOOD_PRESSURE && it.value1 == 138.0 && it.value2 == 86.0 })
        assertTrue(stackedOcr.any { it.type == VitalType.HEART_RATE && it.value1 == 72.0 })
    }

    @Test
    fun emergencyDispatcherCardFormatsRuAndDeWithAddressAndVitals() {
        val now = 1_700_000_000_000L
        val profile = HealthProfile(
            id = "p1",
            name = "Vladimir Ilchenko",
            birthYear = 1978,
            homeAddress = "Musterstrasse 12, Wuppertal",
            chronicConditions = "Гипертония",
            allergies = "Пенициллин"
        )
        val events = listOf(
            HealthEvent("e1", "p1", now - 600_000L, HealthCategory.SYMPTOM, "Давит ниже горла и в левую руку", severity0to10 = 8)
        )
        val vitals = listOf(
            VitalMeasurement("v1", "p1", now - 300_000L, VitalType.BLOOD_PRESSURE, 178.0, 106.0)
        )
        val cardDe = RedFlagSafetyEngine.buildEmergencyDispatcherCard(profile, events, vitals, "51.25620, 7.15080 (GPS)", ReportLanguage.DE, now)
        assertTrue(cardDe.contains("Vladimir Ilchenko"))
        assertTrue(cardDe.contains("Musterstrasse 12, Wuppertal"))
        assertTrue(cardDe.contains("178 zu 106"))
        assertTrue(cardDe.contains("NOTRUF-INFORMATION"))

        val cardRu = RedFlagSafetyEngine.buildEmergencyDispatcherCard(profile, events, vitals, "51.25620, 7.15080 (GPS)", ReportLanguage.RU, now)
        assertTrue(cardRu.contains("ЭКСТРЕННАЯ КАРТОЧКА"))
        assertTrue(cardRu.contains("178/106"))
        assertTrue(cardRu.contains("Musterstrasse 12, Wuppertal"))
    }

    @Test
    fun updateCheckerParsesNewerVersionJsonOnly() {
        val rawNewer = """{"code": 3, "name": "1.0.2", "notes": "Улучшен OCR тонометра"}"""
        val upd = UpdateChecker.parseVersionJson(rawNewer, currentCode = 2L)
        assertNotNull(upd)
        assertEquals(3L, upd!!.code)
        assertEquals("1.0.2", upd.name)
        assertTrue(upd.apkUrl.endsWith("/MyHealth.apk"))

        val rawSame = """{"code": 2, "name": "1.0.1", "notes": "Текущая"}"""
        assertNull(UpdateChecker.parseVersionJson(rawSame, currentCode = 2L))
    }
}
