package com.myhealth.ai.logic

import com.myhealth.ai.data.HealthPrefs
import com.myhealth.ai.model.ActionSuggestion
import com.myhealth.ai.model.AiSettings
import com.myhealth.ai.model.ClarifyingQuestion
import com.myhealth.ai.model.CorrelationInsight
import com.myhealth.ai.model.DataSourceTrust
import com.myhealth.ai.model.HealthCategory
import com.myhealth.ai.model.HealthEvent
import com.myhealth.ai.model.HealthProfile
import com.myhealth.ai.model.IntakeEntry
import com.myhealth.ai.model.IntakeKind
import com.myhealth.ai.model.LabFlag
import com.myhealth.ai.model.LabMarker
import com.myhealth.ai.model.MedicalDocument
import com.myhealth.ai.model.ReportLanguage
import com.myhealth.ai.model.VitalMeasurement
import com.myhealth.ai.model.WeatherSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.UUID

data class MealPhotoAnalysis(
    val ok: Boolean,
    val entry: IntakeEntry?,
    val summaryText: String
)

data class DocumentAiExtraction(
    val title: String,
    val doctorOrClinic: String,
    val summary: String,
    val markers: List<LabMarker>
)

data class DevicePhotoReading(
    val systolic: Int?,
    val diastolic: Int?,
    val pulse: Int?,
    val note: String = ""
)

data class GeminiSymptomParseResult(
    val ok: Boolean,
    val events: List<HealthEvent>,
    val intakes: List<IntakeEntry>,
    val followUpQuestions: List<ClarifyingQuestion>,
    val actionSuggestions: List<ActionSuggestion> = emptyList(),
    val guidanceText: String
)

/**
 * Gemini API client built on the current Interactions API (/v1/interactions)
 * using gemini-3.8-flash by default and store=false for sensitive health privacy.
 * Always gated behind explicit user consent (aiConsentGranted) + API key.
 */
object GeminiHealthClient {

    private const val INTERACTIONS_ENDPOINT = "https://generativelanguage.googleapis.com/v1/interactions"

    fun testConnection(settings: AiSettings): Result<String> {
        if (settings.geminiApiKey.isBlank()) {
            return Result.failure(IllegalStateException("Введите ключ Gemini API"))
        }
        return runCatching {
            val model = HealthPrefs.sanitizeModel(settings.geminiModel)
            val resp = callInteractions(
                apiKey = settings.geminiApiKey.trim(),
                model = model,
                systemInstruction = "Отвечай предельно кратко.",
                prompt = "Ответь ровно одним словом: OK",
                imageBytes = null
            )
            "Подключено ($model): ${resp.trim().take(40)}"
        }
    }

    fun analyzeSymptomAndClarify(
        rawText: String,
        profile: HealthProfile,
        weather: WeatherSnapshot?,
        priorEvents: List<HealthEvent> = emptyList(),
        recentVitals: List<VitalMeasurement> = emptyList(),
        recentIntakes: List<IntakeEntry> = emptyList(),
        settings: AiSettings,
        nowMillis: Long = System.currentTimeMillis()
    ): GeminiSymptomParseResult? {
        if (!settings.isGeminiReady || rawText.isBlank()) return null

        val weatherLine = weather?.summaryText ?: "нет данных о погоде"
        val historyLines = priorEvents.take(10).joinToString("; ") { "${it.title} (${it.bodyRegion})" }.ifBlank { "нет прошлых записей" }
        val vitalLines = recentVitals.take(6).joinToString("; ") {
            if (it.value2 != null) "${it.type.titleRu} ${it.value1.toInt()}/${it.value2!!.toInt()}" else "${it.type.titleRu} ${it.value1}"
        }.ifBlank { "свежих замеров нет" }
        val intakeLines = recentIntakes.take(6).joinToString("; ") { "${it.name} ${it.notes}".trim() }.ifBlank { "данных о еде/лекарствах нет" }
        val prompt = """
            Профиль: ${profile.name}, год рожд.: ${profile.birthYear ?: "?"}, хронические состояния: ${profile.chronicConditions.ifBlank { "не указаны" }}, лекарства: ${profile.medications.ifBlank { "не указаны" }}, аллергии/триггеры: ${profile.allergies.ifBlank { "не указаны" }}.
            Прошлые похожие/недавние события: $historyLines.
            Последние замеры: $vitalLines.
            Недавнее питание/кофе/лекарства: $intakeLines.
            Текущая погода: $weatherLine.
            Новая надиктовка пользователя (симптом И/ИЛИ общее состояние): "$rawText"

            Верни ТОЛЬКО валидный JSON-объект (без markdown) в таком формате:
            {
              "category": "SYMPTOM" | "GI_DIGESTION" | "FOOD_DRINK" | "VITALS" | "SLEEP_ACTIVITY" | "NOTE",
              "title": "краткий ясный заголовок (до 60 символов)",
              "bodyRegion": "часть тела или пусто",
              "painCharacter": "характер боли или пусто",
              "severity0to10": число 0..10 или null,
              "tags": ["тег1", "тег2"],
              "followUpQuestions": [
                {"key": "q1", "promptRu": "Короткий уточняющий вопрос?", "quickOptions": ["Вариант 1", "Вариант 2", "Вариант 3"]}
              ],
              "actionRequests": [
                {"key": "measure_bp", "titleRu": "Что измерить/сделать прямо сейчас", "reasonRu": "Зачем это нужно для системной оценки"}
              ],
              "guidanceText": "целостный ИИ-разбор состояния (учитывай историю, питание, сон, давление, хронику vs острое состояние; не лечи орган изолированно; не ставь диагноз)"
            }
            Правила:
            - Если жалоба повторялась в прошлом (хроническое онемение пальца и т.п.) без слов про резкое усиление — отметь это как плановое наблюдение, а не панический инсульт.
            - Если есть головокружение/головная боль/давление под челюстью/в груди/тошнота и нет свежего АД — обязательно добавь в actionRequests шаг "Померяйте сейчас давление и пульс" (key="measure_bp").
            - Задавай не более 3 уточняющих вопросов.
        """.trimIndent()

        return runCatching {
            val model = HealthPrefs.sanitizeModel(settings.geminiModel)
            val raw = callInteractions(
                apiKey = settings.geminiApiKey.trim(),
                model = model,
                systemInstruction = "Ты аккуратный системный медицинский ассистент-дневник. Не заменяешь врача, не ставишь диагноз, связываешь симптомы, хронические факторы, замеры и внешнюю среду воедино.",
                prompt = prompt,
                imageBytes = null
            )
            parseSymptomJson(raw, rawText, profile.id, weather?.id, model, nowMillis)
        }.getOrNull()
    }

    fun runFullSystemicAnalysis(
        currentNote: String,
        profile: HealthProfile,
        weather: WeatherSnapshot?,
        events: List<HealthEvent>,
        vitals: List<VitalMeasurement>,
        intakes: List<IntakeEntry>,
        correlations: List<CorrelationInsight>,
        settings: AiSettings
    ): String? {
        if (!settings.isGeminiReady) return null
        val recentEvents = events.take(20).joinToString("\n") { "- ${it.title} (${it.bodyRegion}, ${it.severity0to10 ?: "-"}/10) ${it.notes.take(120)}" }.ifBlank { "пока нет" }
        val recentVitals = vitals.take(10).joinToString("\n") { v ->
            val vText = if (v.value2 != null) "${v.value1.toInt()}/${v.value2.toInt()} ${v.unit}" else "${v.value1} ${v.unit}"
            "- ${v.type.titleRu}: $vText (${v.sourceTrust.badgeRu})"
        }.ifBlank { "нет замеров" }
        val recentIntakes = intakes.take(10).joinToString("; ") { "${it.kind.titleRu}: ${it.name}" }.ifBlank { "нет" }
        val corrLines = correlations.take(5).joinToString("\n") { "- ${it.summaryText}" }.ifBlank { "пока мало статистики" }
        val prompt = """
            Проведи целостный ИИ-разбор текущего состояния и всех накопленных данных (на русском языке, понятно, без диагнозов).
            Пациент: ${profile.name}, г.р. ${profile.birthYear ?: "?"}.
            Хронические состояния: ${profile.chronicConditions.ifBlank { "не указаны" }}.
            Лекарства: ${profile.medications.ifBlank { "не указаны" }}.
            Аллергии / триггеры: ${profile.allergies.ifBlank { "не указаны" }}.
            Погода и давление: ${weather?.summaryText ?: "нет"}.

            ТЕКУЩАЯ НАДИКТОВКА / ЗАМЕТКА ПОЛЬЗОВАТЕЛЯ:
            "${currentNote.ifBlank { "(без нового текста — обзор последних событий)" }}"

            ПОСЛЕДНИЕ СОБЫТИЯ И СИМПТОМЫ:
            $recentEvents

            ЗАМЕРЫ ПРИБОРОВ:
            $recentVitals

            ЕДА / НАПИТКИ / ЛЕКАРСТВА:
            $recentIntakes

            ЗАМЕЧЕННЫЕ СТАТИСТИЧЕСКИЕ СВЯЗИ:
            $corrLines

            Структура ответа:
            1) Целостная картина и возможные связи факторов (что с чем может пересекаться, учитывая хронику vs острые признаки);
            2) Что измерить или уточнить прямо сейчас (АД/пульс/t°C/фото тонометра, питание, сон);
            3) На что обратить внимание при обращении к врачу (и при каких признаках звонить 112).
        """.trimIndent()
        return runCatching {
            val model = HealthPrefs.sanitizeModel(settings.geminiModel)
            callInteractions(
                apiKey = settings.geminiApiKey.trim(),
                model = model,
                systemInstruction = "Ты системный ассистент здоровья. Не ставишь диагноз и не заменяешь врача, но помогаешь увидеть общую картину взаимосвязей организма.",
                prompt = prompt,
                imageBytes = null
            ).trim().takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    fun parseSymptomJson(
        rawJson: String,
        originalText: String,
        profileId: String,
        weatherSnapshotId: String?,
        modelLabel: String,
        nowMillis: Long
    ): GeminiSymptomParseResult? {
        val obj = extractJsonObject(rawJson) ?: return null
        val title = obj.optString("title").trim().ifBlank { originalText.take(60) }
        val cat = runCatching {
            HealthCategory.valueOf(obj.optString("category", "SYMPTOM").uppercase())
        }.getOrDefault(HealthCategory.SYMPTOM)
        val sev = if (obj.has("severity0to10") && !obj.isNull("severity0to10")) {
            obj.optInt("severity0to10").coerceIn(0, 10)
        } else null
        val tagsArr = obj.optJSONArray("tags") ?: JSONArray()
        val tags = (0 until tagsArr.length()).mapNotNull { i ->
            tagsArr.optString(i).trim().takeIf { it.isNotEmpty() }
        }

        val qs = mutableListOf<ClarifyingQuestion>()
        val qArr = obj.optJSONArray("followUpQuestions") ?: JSONArray()
        for (i in 0 until qArr.length()) {
            val qObj = qArr.optJSONObject(i) ?: continue
            val pRu = qObj.optString("promptRu").trim()
            if (pRu.isEmpty()) continue
            val optsArr = qObj.optJSONArray("quickOptions") ?: JSONArray()
            val opts = (0 until optsArr.length()).mapNotNull { j ->
                optsArr.optString(j).trim().takeIf { it.isNotEmpty() }
            }
            qs.add(ClarifyingQuestion(qObj.optString("key", "q$i"), pRu, opts))
        }

        val actions = mutableListOf<ActionSuggestion>()
        val aArr = obj.optJSONArray("actionRequests") ?: JSONArray()
        for (i in 0 until aArr.length()) {
            val aObj = aArr.optJSONObject(i) ?: continue
            val tRu = aObj.optString("titleRu").trim()
            if (tRu.isEmpty()) continue
            actions.add(
                ActionSuggestion(
                    key = aObj.optString("key", "act$i"),
                    titleRu = tRu,
                    reasonRu = aObj.optString("reasonRu", "").trim()
                )
            )
        }

        val event = HealthEvent(
            id = UUID.randomUUID().toString(),
            profileId = profileId,
            timestampMillis = nowMillis,
            category = cat,
            title = title,
            notes = originalText,
            severity0to10 = sev,
            bodyRegion = obj.optString("bodyRegion", "").trim(),
            painCharacter = obj.optString("painCharacter", "").trim(),
            tags = tags,
            sourceTrust = DataSourceTrust.USER_INPUT,
            sourceDetail = "Запись + разбор $modelLabel",
            weatherSnapshotId = weatherSnapshotId
        )

        return GeminiSymptomParseResult(
            ok = true,
            events = listOf(event),
            intakes = emptyList(),
            followUpQuestions = qs.take(3),
            actionSuggestions = actions.take(3),
            guidanceText = obj.optString("guidanceText", "").trim()
        )
    }

    fun analyzeMealPhoto(
        imageBytes: ByteArray,
        photoPath: String,
        profileId: String,
        settings: AiSettings,
        nowMillis: Long = System.currentTimeMillis()
    ): MealPhotoAnalysis {
        if (!settings.isGeminiReady) {
            return MealPhotoAnalysis(false, null, "Включите согласие ИИ и введите ключ Gemini в Профиле")
        }
        val prompt = """
            По фотографии тарелки оцени блюдо и порцию.
            Верни ТОЛЬКО валидный JSON-объект:
            {
              "name": "краткое название блюда на русском",
              "portionGrams": ориентировочный вес порции в граммах (число),
              "estimatedKcal": ориентировочные ккал (целое число),
              "proteinG": белки в г (число),
              "fatG": жиры в г (число),
              "carbsG": углеводы в г (число),
              "notes": "предполагаемый состав и возможные пищевые триггеры (например молочное, кофеин, острое, глютен)"
            }
        """.trimIndent()

        return runCatching {
            val model = HealthPrefs.sanitizeModel(settings.geminiModel)
            val raw = callInteractions(
                apiKey = settings.geminiApiKey.trim(),
                model = model,
                systemInstruction = "Оценивай питание ориентировочно; подчёркивай приблизительный характер расчёта.",
                prompt = prompt,
                imageBytes = imageBytes
            )
            val obj = extractJsonObject(raw) ?: error("Не удалось разобрать JSON ответа ИИ")
            val name = obj.optString("name", "Блюдо по фото").trim()
            val entry = IntakeEntry(
                id = UUID.randomUUID().toString(),
                profileId = profileId,
                timestampMillis = nowMillis,
                kind = IntakeKind.MEAL,
                name = name,
                amountValue = obj.optDouble("portionGrams", 250.0),
                amountUnit = "г",
                estimatedKcal = obj.optInt("estimatedKcal", 0).takeIf { it > 0 },
                proteinG = obj.optDouble("proteinG", -1.0).takeIf { it >= 0 },
                fatG = obj.optDouble("fatG", -1.0).takeIf { it >= 0 },
                carbsG = obj.optDouble("carbsG", -1.0).takeIf { it >= 0 },
                photoPath = photoPath,
                sourceTrust = DataSourceTrust.AI_HYPOTHESIS,
                notes = obj.optString("notes", "").trim()
            )
            val summary = "$name · ~${entry.estimatedKcal ?: "?"} ккал " +
                "(Б ${entry.proteinG?.toInt() ?: "?"} / Ж ${entry.fatG?.toInt() ?: "?"} / У ${entry.carbsG?.toInt() ?: "?"} г) [Оценка ИИ]"
            MealPhotoAnalysis(true, entry, summary)
        }.getOrElse { ex ->
            MealPhotoAnalysis(false, null, "Ошибка анализа фото: ${ex.message}")
        }
    }

    fun analyzeDevicePhoto(
        imageBytes: ByteArray?,
        ocrText: String,
        settings: AiSettings
    ): DevicePhotoReading? {
        if (!settings.isGeminiReady || imageBytes == null) return null
        val prompt = """
            Перед тобой фотография экрана тонометра, пульсоксиметра или умных часов.
            Черновой текст OCR: ${ocrText.take(400)}

            Считай точные цифры с экрана прибора. Верни ТОЛЬКО валидный JSON:
            {
              "systolic": целое число верхнего АД или null,
              "diastolic": целое число нижнего АД или null,
              "pulse": целое число пульса (уд/мин) или null,
              "note": "короткое примечание (прибор/ритм)"
            }
        """.trimIndent()
        return runCatching {
            val model = HealthPrefs.sanitizeModel(settings.geminiModel)
            val raw = callInteractions(
                apiKey = settings.geminiApiKey.trim(),
                model = model,
                systemInstruction = "Ты точно распознаёшь показания медицинских приборов на фото.",
                prompt = prompt,
                imageBytes = imageBytes
            )
            val obj = extractJsonObject(raw) ?: return@runCatching null
            val sys = obj.optInt("systolic", -1).takeIf { it in 60..280 }
            val dia = obj.optInt("diastolic", -1).takeIf { it in 35..180 }
            val pulse = obj.optInt("pulse", -1).takeIf { it in 30..240 }
            if (sys == null && dia == null && pulse == null) return@runCatching null
            DevicePhotoReading(sys, dia, pulse, obj.optString("note", "").trim())
        }.getOrNull()
    }

    fun analyzeMedicalDocumentWithVision(
        imageBytes: ByteArray?,
        ocrText: String,
        documentId: String,
        profileId: String,
        measuredAtMillis: Long,
        settings: AiSettings
    ): DocumentAiExtraction? {
        if (!settings.isGeminiReady) return null
        val prompt = """
            Перед тобой медицинский документ / бланк анализа / фото экрана прибора (тонометр, глюкометр или тест-полоска мочи).
            Текст OCR (может содержать опечатки):
            ${ocrText.take(4000)}

            Верни ТОЛЬКО валидный JSON-объект:
            {
              "title": "краткое название документа или анализа",
              "doctorOrClinic": "лаборатория/врач или пусто",
              "summary": "краткая выжимка (2-4 предложения) строго по фактам из документа, без выдумывания диагнозов",
              "markers": [
                {
                  "markerName": "название показателя",
                  "value": число,
                  "unit": "единица измерения",
                  "refMin": число или null,
                  "refMax": число или null,
                  "flag": "LOW" | "NORMAL" | "HIGH" | "UNKNOWN"
                }
              ]
            }
            Важно: для тест-полоски мочи указывай именно «pH мочи», не «pH организма».
        """.trimIndent()

        return runCatching {
            val model = HealthPrefs.sanitizeModel(settings.geminiModel)
            val raw = callInteractions(
                apiKey = settings.geminiApiKey.trim(),
                model = model,
                systemInstruction = "Извлекай лабораторные показатели точно и аккуратно. Если значение нечёткое — не выдумывай.",
                prompt = prompt,
                imageBytes = imageBytes
            )
            parseDocumentExtractionJson(raw, documentId, profileId, measuredAtMillis)
        }.getOrNull()
    }

    fun parseDocumentExtractionJson(
        rawJson: String,
        documentId: String,
        profileId: String,
        measuredAtMillis: Long
    ): DocumentAiExtraction? {
        val obj = extractJsonObject(rawJson) ?: return null
        val markers = mutableListOf<LabMarker>()
        val arr = obj.optJSONArray("markers") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val name = m.optString("markerName").trim()
            if (name.isEmpty() || !m.has("value") || m.isNull("value")) continue
            val value = m.optDouble("value", Double.NaN)
            if (value.isNaN()) continue
            val refMin = if (m.has("refMin") && !m.isNull("refMin")) m.optDouble("refMin") else null
            val refMax = if (m.has("refMax") && !m.isNull("refMax")) m.optDouble("refMax") else null
            val flag = runCatching {
                LabFlag.valueOf(m.optString("flag", "UNKNOWN").uppercase())
            }.getOrDefault(LabFlag.UNKNOWN)

            markers.add(
                LabMarker(
                    id = UUID.randomUUID().toString(),
                    documentId = documentId,
                    profileId = profileId,
                    measuredAtMillis = measuredAtMillis,
                    markerName = name,
                    value = value,
                    unit = m.optString("unit", "").trim(),
                    refMin = refMin,
                    refMax = refMax,
                    flag = flag,
                    userVerified = false
                )
            )
        }
        return DocumentAiExtraction(
            title = obj.optString("title", "").trim(),
            doctorOrClinic = obj.optString("doctorOrClinic", "").trim(),
            summary = obj.optString("summary", "").trim(),
            markers = markers
        )
    }

    fun buildDoctorVisitReflection(
        profile: HealthProfile,
        events: List<HealthEvent>,
        vitals: List<VitalMeasurement>,
        correlations: List<CorrelationInsight>,
        docs: List<MedicalDocument>,
        lang: ReportLanguage,
        settings: AiSettings
    ): String? {
        if (!settings.isGeminiReady) return null
        val langWord = if (lang == ReportLanguage.DE) "немецком (Deutsch)" else "русском"
        val prompt = """
            Подготовь структурированную памятку для очного визита к врачу на языке: $langWord.
            Пациент: ${profile.name}, лекарства: ${profile.medications.ifBlank { "-" }}, аллергии: ${profile.allergies.ifBlank { "-" }}.
            Последние симптомы (${events.take(15).size}):
            ${events.take(15).joinToString("\n") { "- ${it.title} (тяжесть: ${it.severity0to10 ?: "-"}/10, теги: ${it.tags.joinToString()})" }}
            Последние замеры (${vitals.take(15).size}):
            ${vitals.take(15).joinToString("\n") { "- ${it.type.titleRu}: ${it.value1}${if (it.value2 != null) "/${it.value2!!.toInt()}" else ""} ${it.unit} [${it.sourceTrust.badgeRu}]" }}
            Статистические наблюдения:
            ${correlations.take(5).joinToString("\n") { "- ${it.summaryText}" }}
            Документы: ${docs.take(8).joinToString { it.title }}

            Разбей ответ строго на 4 блока:
            1. Информационные наблюдения (только факты)
            2. Наблюдения по образу жизни
            3. Что уточнить и обсудить на приёме у врача (3-5 вопросов)
            4. Напоминание о красных флагах (когда нужна срочная помощь).
            Не выставляй окончательный диагноз и не отменяй назначения.
        """.trimIndent()

        return runCatching {
            val model = HealthPrefs.sanitizeModel(settings.geminiModel)
            callInteractions(
                apiKey = settings.geminiApiKey.trim(),
                model = model,
                systemInstruction = "Ты помощник подготовки к приёму врача. Не выставляй диагнозы; формулируй чётко и уважительно.",
                prompt = prompt,
                imageBytes = null
            ).trim().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    internal fun callInteractions(
        apiKey: String,
        model: String,
        systemInstruction: String,
        prompt: String,
        imageBytes: ByteArray?
    ): String {
        val inputItems = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", prompt))
            if (imageBytes != null) {
                put(
                    JSONObject()
                        .put("type", "image")
                        .put("data", Base64.getEncoder().encodeToString(imageBytes))
                        .put("mime_type", "image/jpeg")
                )
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("store", false)
            .put("system_instruction", systemInstruction)
            .put("input", inputItems)

        val conn = (URL(INTERACTIONS_ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 12_000
            readTimeout = 35_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val respText = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
        conn.disconnect()
        if (code !in 200..299) {
            throw IllegalStateException("Gemini HTTP $code: ${respText.take(180)}")
        }
        return extractInteractionText(respText)
    }

    fun extractInteractionText(responseJson: String): String {
        val root = JSONObject(responseJson)
        val sb = StringBuilder()

        // Primary v1 Interactions response structure: steps[] with type == "model_output"
        val steps = root.optJSONArray("steps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("type") == "model_output") {
                    val content = step.optJSONArray("content") ?: continue
                    for (j in 0 until content.length()) {
                        val part = content.optJSONObject(j) ?: continue
                        if (part.optString("type") == "text") {
                            val t = part.optString("text", "")
                            if (t.isNotEmpty()) sb.append(t)
                        }
                    }
                }
            }
        }
        if (sb.isNotEmpty()) return sb.toString()

        // Fallback outputs[] shape if returned by beta variants
        val outputs = root.optJSONArray("outputs")
        if (outputs != null) {
            for (i in 0 until outputs.length()) {
                val part = outputs.optJSONObject(i) ?: continue
                if (part.optString("type") == "text") {
                    val t = part.optString("text", "")
                    if (t.isNotEmpty()) sb.append(t)
                }
            }
        }
        return sb.toString()
    }

    private fun extractJsonObject(raw: String): JSONObject? {
        val cleaned = raw
            .replace(Regex("```json\\s*", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { JSONObject(cleaned.substring(start, end + 1)) }.getOrNull()
    }
}
