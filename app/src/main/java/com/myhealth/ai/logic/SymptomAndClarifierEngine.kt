package com.myhealth.ai.logic

import com.myhealth.ai.model.ActionSuggestion
import com.myhealth.ai.model.ClarifyingQuestion
import com.myhealth.ai.model.DataSourceTrust
import com.myhealth.ai.model.HealthCategory
import com.myhealth.ai.model.HealthEvent
import com.myhealth.ai.model.IntakeEntry
import com.myhealth.ai.model.IntakeKind
import com.myhealth.ai.model.VitalMeasurement
import com.myhealth.ai.model.VitalType
import java.util.UUID

data class ParsedUserInput(
    val events: List<HealthEvent>,
    val vitals: List<VitalMeasurement>,
    val intakes: List<IntakeEntry>,
    val missingQuestions: List<ClarifyingQuestion>,
    val actionSuggestions: List<ActionSuggestion> = emptyList()
)

/**
 * Offline multilingual (RU/UK/DE) parser for quick voice/text health captures.
 * Extracts symptoms, vital measurements, intakes, missing clarifying questions,
 * and proactive next diagnostic steps ("measure BP/pulse right now").
 */
object SymptomAndClarifierEngine {

    private val bpRegex = Regex("""(?:давлен\w*|ад|sys|blutdruck)?\s*(\d{2,3})\s*[/\\на]\s*(\d{2,3})""", RegexOption.IGNORE_CASE)
    private val sysDiaRegex = Regex("""(?:sys|сис)\D{0,6}(\d{2,3})\D{1,12}(?:dia|диа)\D{0,6}(\d{2,3})""", RegexOption.IGNORE_CASE)
    private val pulseRegex = Regex("""(?:пульс|чсс|ударов|puls|pul|herzfrequenz)\s*[:=]?\s*(\d{2,3})""", RegexOption.IGNORE_CASE)
    private val tempRegex = Regex("""(?:температур\w*|темп)\s*[:=]?\s*(3[4-9](?:[.,]\d)?|4[0-2](?:[.,]\d)?)""", RegexOption.IGNORE_CASE)
    private val sleepRegex = Regex("""(?:спал\w*|сон)\s*[:=]?\s*(\d{1,2}(?:[.,]\d)?)\s*(?:ч|час|std)?""", RegexOption.IGNORE_CASE)
    private val weightRegex = Regex("""(?:вес|gewicht)\s*[:=]?\s*(\d{2,3}(?:[.,]\d)?)""", RegexOption.IGNORE_CASE)
    private val severityRegex = Regex("""(?:на\s+)?(10|[0-9])\s*(?:из\s*10|/10|балл)""", RegexOption.IGNORE_CASE)
    private val countCupRegex = Regex("""(\d+(?:[.,]\d+)?)\s*(?:круж\w*|чаш\w*|стакан\w*|табл\w*|бокал\w*|рюм\w*|г|гр|мл|л)""", RegexOption.IGNORE_CASE)

    fun parseOffline(
        rawText: String,
        profileId: String,
        selectedTags: Set<String> = emptySet(),
        explicitSeverity: Int? = null,
        weatherSnapshotId: String? = null,
        priorEvents: List<HealthEvent> = emptyList(),
        recentVitals: List<VitalMeasurement> = emptyList(),
        nowMillis: Long = System.currentTimeMillis()
    ): ParsedUserInput {
        val text = rawText.trim()
        val lower = text.lowercase()
        val events = mutableListOf<HealthEvent>()
        val vitals = mutableListOf<VitalMeasurement>()
        val intakes = mutableListOf<IntakeEntry>()

        // 1. Extract objective vitals if mentioned
        extractBpAndPulseFromText(text, profileId, DataSourceTrust.USER_INPUT, "Голосовой/текстовый ввод", nowMillis)
            .forEach { vitals.add(it) }

        tempRegex.find(lower)?.let { m ->
            val t = m.groupValues[1].replace(',', '.').toDoubleOrNull()
            if (t != null && t in 34.0..42.5) {
                vitals.add(
                    VitalMeasurement(
                        id = UUID.randomUUID().toString(),
                        profileId = profileId,
                        measuredAtMillis = nowMillis,
                        type = VitalType.TEMPERATURE_C,
                        value1 = t,
                        unit = VitalType.TEMPERATURE_C.defaultUnit,
                        sourceTrust = DataSourceTrust.USER_INPUT,
                        sourceDetail = "Голосовой/текстовый ввод"
                    )
                )
            }
        }

        sleepRegex.find(lower)?.let { m ->
            val hrs = m.groupValues[1].replace(',', '.').toDoubleOrNull()
            if (hrs != null && hrs in 0.5..20.0) {
                vitals.add(
                    VitalMeasurement(
                        id = UUID.randomUUID().toString(),
                        profileId = profileId,
                        measuredAtMillis = nowMillis,
                        type = VitalType.SLEEP_HOURS,
                        value1 = hrs,
                        unit = VitalType.SLEEP_HOURS.defaultUnit,
                        sourceTrust = DataSourceTrust.USER_INPUT,
                        sourceDetail = "Голосовой/текстовый ввод"
                    )
                )
            }
        }

        weightRegex.find(lower)?.let { m ->
            val w = m.groupValues[1].replace(',', '.').toDoubleOrNull()
            if (w != null && w in 25.0..260.0) {
                vitals.add(
                    VitalMeasurement(
                        id = UUID.randomUUID().toString(),
                        profileId = profileId,
                        measuredAtMillis = nowMillis,
                        type = VitalType.WEIGHT_KG,
                        value1 = w,
                        unit = VitalType.WEIGHT_KG.defaultUnit,
                        sourceTrust = DataSourceTrust.USER_INPUT,
                        sourceDetail = "Голосовой/текстовый ввод"
                    )
                )
            }
        }

        // 2. Extract intakes (coffee, tea, water, alcohol, caffeine pill, smoking)
        val countVal = countCupRegex.find(lower)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()
        if (lower.contains("кофе") || selectedTags.contains("Кофе")) {
            val isPill = lower.contains("таблетк")
            intakes.add(
                IntakeEntry(
                    id = UUID.randomUUID().toString(),
                    profileId = profileId,
                    timestampMillis = nowMillis,
                    kind = if (isPill) IntakeKind.CAFFEINE_PILL else IntakeKind.COFFEE,
                    name = if (isPill) "Таблетка кофеина" else "Кофе",
                    amountValue = countVal ?: 1.0,
                    amountUnit = if (isPill) "табл." else "чаш.",
                    notes = text
                )
            )
        }
        if (lower.contains("чай") || lower.contains("чаю")) {
            intakes.add(
                IntakeEntry(
                    id = UUID.randomUUID().toString(),
                    profileId = profileId,
                    timestampMillis = nowMillis,
                    kind = IntakeKind.TEA,
                    name = "Чай",
                    amountValue = countVal ?: 1.0,
                    amountUnit = "чаш."
                )
            )
        }
        if (lower.contains("вод") && (lower.contains("выпил") || lower.contains("стакан") || lower.contains("мл") || lower.contains("литр") || selectedTags.contains("Вода"))) {
            intakes.add(
                IntakeEntry(
                    id = UUID.randomUUID().toString(),
                    profileId = profileId,
                    timestampMillis = nowMillis,
                    kind = IntakeKind.WATER,
                    name = "Вода",
                    amountValue = countVal ?: 250.0,
                    amountUnit = if (lower.contains("стакан")) "стак." else "мл"
                )
            )
        }
        if (lower.contains("алкогол") || lower.contains("вин") || lower.contains("пив") || lower.contains("водк") || lower.contains("коньяк") || selectedTags.contains("Алкоголь")) {
            intakes.add(
                IntakeEntry(
                    id = UUID.randomUUID().toString(),
                    profileId = profileId,
                    timestampMillis = nowMillis,
                    kind = IntakeKind.ALCOHOL,
                    name = extractAlcoholName(lower),
                    amountValue = countVal,
                    amountUnit = if (lower.contains("г")) "г/мл" else "порц.",
                    notes = text
                )
            )
        }
        if ((lower.contains("таблет") && !lower.contains("кофеин")) || lower.contains("ибупрофен") || lower.contains("суматриптан") || lower.contains("принял лекар") || selectedTags.contains("Таблетка")) {
            intakes.add(
                IntakeEntry(
                    id = UUID.randomUUID().toString(),
                    profileId = profileId,
                    timestampMillis = nowMillis,
                    kind = IntakeKind.MEDICATION,
                    name = if (text.isNotBlank()) text.take(60) else "Лекарство",
                    amountValue = countVal ?: 1.0,
                    amountUnit = "табл."
                )
            )
        }

        // 3. Extract symptom or GI event
        val detectedSeverity = explicitSeverity ?: severityRegex.find(lower)?.groupValues?.get(1)?.toIntOrNull()
        val region = detectBodyRegion(lower, selectedTags)
        val painChar = detectPainCharacter(lower)
        val allTags = (selectedTags + inferTags(lower)).distinct()

        val hasSymptomKeyword = lower.contains("бол") || lower.contains("мигрен") || lower.contains("колет") ||
            lower.contains("давит") || lower.contains("сдав") || lower.contains("душ") ||
            lower.contains("тянет") || lower.contains("немеет") || lower.contains("холодн") ||
            lower.contains("чувствит") || lower.contains("круж") || lower.contains("тошн") ||
            lower.contains("изжог") || lower.contains("запор") || lower.contains("понос") ||
            lower.contains("стул") || lower.contains("аллерг") || lower.contains("слабост") ||
            lower.contains("плохо") || lower.contains("недосып") || lower.contains("стресс") ||
            lower.contains("аритм") || selectedTags.any { it in SYMPTOM_TAGS }

        val combinedInput = listOf(text, selectedTags.joinToString(" ")).filter { it.isNotBlank() }.joinToString(" ")
        val allCombinedVitals = vitals + recentVitals
        val redFlag = RedFlagSafetyEngine.evaluate(combinedInput, detectedSeverity, allCombinedVitals, priorEvents, nowMillis)
        val suggestions = buildActionSuggestions(combinedInput, allCombinedVitals, nowMillis)

        if (hasSymptomKeyword || (text.isNotBlank() && vitals.isEmpty() && intakes.isEmpty())) {
            val category = when {
                lower.contains("изжог") || lower.contains("живот") || lower.contains("стул") || lower.contains("запор") || lower.contains("понос") || selectedTags.contains("Изжога/ЖКТ") ->
                    HealthCategory.GI_DIGESTION
                lower.contains("недосып") || lower.contains("бессон") ->
                    HealthCategory.SLEEP_ACTIVITY
                else -> HealthCategory.SYMPTOM
            }
            val title = buildReadableTitle(text, selectedTags, region)
            val event = HealthEvent(
                id = UUID.randomUUID().toString(),
                profileId = profileId,
                timestampMillis = nowMillis,
                category = category,
                title = title,
                notes = text,
                severity0to10 = detectedSeverity,
                bodyRegion = region,
                painCharacter = painChar,
                startedAtMillis = inferStartedAtMillis(lower, nowMillis),
                tags = allTags,
                sourceTrust = DataSourceTrust.USER_INPUT,
                sourceDetail = if (text.isNotBlank()) "Голос/текст" else "Быстрые теги",
                weatherSnapshotId = weatherSnapshotId,
                isRedFlagTriggered = redFlag.isUrgent
            )
            events.add(event)
            val questions = buildMissingClarifyingQuestions(event, lower, priorEvents)
            return ParsedUserInput(events, vitals, intakes, questions, suggestions)
        }

        if (vitals.isNotEmpty()) {
            val summary = vitals.joinToString(", ") { v ->
                if (v.value2 != null) "${v.type.titleRu}: ${v.value1.toInt()}/${v.value2.toInt()} ${v.unit}"
                else "${v.type.titleRu}: ${formatDouble(v.value1)} ${v.unit}"
            }
            events.add(
                HealthEvent(
                    id = UUID.randomUUID().toString(),
                    profileId = profileId,
                    timestampMillis = nowMillis,
                    category = HealthCategory.VITALS,
                    title = summary,
                    notes = text,
                    tags = allTags,
                    sourceTrust = DataSourceTrust.USER_INPUT,
                    sourceDetail = "Быстрый ввод замера",
                    weatherSnapshotId = weatherSnapshotId,
                    isRedFlagTriggered = redFlag.isUrgent
                )
            )
        } else if (intakes.isNotEmpty()) {
            val summary = intakes.joinToString(", ") { "${it.name}${if (it.amountValue != null) " (${formatDouble(it.amountValue)} ${it.amountUnit})" else ""}" }
            events.add(
                HealthEvent(
                    id = UUID.randomUUID().toString(),
                    profileId = profileId,
                    timestampMillis = nowMillis,
                    category = HealthCategory.FOOD_DRINK,
                    title = summary,
                    notes = text,
                    tags = allTags,
                    sourceTrust = DataSourceTrust.USER_INPUT,
                    sourceDetail = "Учёт привычек/питания",
                    weatherSnapshotId = weatherSnapshotId
                )
            )
        }

        return ParsedUserInput(events, vitals, intakes, emptyList(), suggestions)
    }

    /**
     * Parses BP and Pulse from voice/text OR OCR of a tonometer device screen.
     */
    fun extractBpAndPulseFromText(
        rawText: String,
        profileId: String,
        trust: DataSourceTrust,
        sourceDetail: String,
        nowMillis: Long = System.currentTimeMillis(),
        attachmentPath: String? = null
    ): List<VitalMeasurement> {
        val list = mutableListOf<VitalMeasurement>()
        val lower = rawText.lowercase()
        val bpMatch = bpRegex.find(lower) ?: sysDiaRegex.find(lower)
        var sysVal: Int? = bpMatch?.groupValues?.get(1)?.toIntOrNull()
        var diaVal: Int? = bpMatch?.groupValues?.get(2)?.toIntOrNull()
        var pulseVal: Int? = pulseRegex.find(lower)?.groupValues?.get(1)?.toIntOrNull()

        if ((sysVal == null || diaVal == null) && attachmentPath != null) {
            val bareNums = Regex("""(?<!\d)(\d{2,3})(?!\d)""")
                .findAll(lower)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .toList()
            for (i in 0 until bareNums.size - 1) {
                val a = bareNums[i]
                val b = bareNums[i + 1]
                if (a in 75..260 && b in 40..160 && (a - b) >= 15) {
                    sysVal = a
                    diaVal = b
                    if (pulseVal == null && i + 2 < bareNums.size && bareNums[i + 2] in 35..220) {
                        pulseVal = bareNums[i + 2]
                    }
                    break
                }
            }
        }

        if (sysVal != null && diaVal != null && sysVal in 60..280 && diaVal in 35..180) {
            list.add(
                VitalMeasurement(
                    id = UUID.randomUUID().toString(),
                    profileId = profileId,
                    measuredAtMillis = nowMillis,
                    type = VitalType.BLOOD_PRESSURE,
                    value1 = sysVal.toDouble(),
                    value2 = diaVal.toDouble(),
                    unit = VitalType.BLOOD_PRESSURE.defaultUnit,
                    sourceTrust = trust,
                    sourceDetail = sourceDetail,
                    attachmentPath = attachmentPath
                )
            )
        }
        pulseVal?.let { hr ->
            if (hr in 30..240) {
                list.add(
                    VitalMeasurement(
                        id = UUID.randomUUID().toString(),
                        profileId = profileId,
                        measuredAtMillis = nowMillis,
                        type = VitalType.HEART_RATE,
                        value1 = hr.toDouble(),
                        unit = VitalType.HEART_RATE.defaultUnit,
                        contextTag = if (lower.contains("штанг") || lower.contains("спорт") || lower.contains("зал")) "после нагрузки" else "",
                        sourceTrust = trust,
                        sourceDetail = sourceDetail,
                        attachmentPath = attachmentPath
                    )
                )
            }
        }
        return list
    }

    /**
     * Suggests concrete diagnostic checks right now (e.g. "Measure BP & Pulse") when symptoms lack recent objective vitals.
     */
    fun buildActionSuggestions(
        rawText: String,
        recentVitals: List<VitalMeasurement>,
        nowMillis: Long
    ): List<ActionSuggestion> {
        val lower = rawText.lowercase()
        val suggestions = mutableListOf<ActionSuggestion>()
        val twoHoursMs = 2 * 3_600_000L
        val hasRecentBp = recentVitals.any { it.type == VitalType.BLOOD_PRESSURE && (nowMillis - it.measuredAtMillis) <= twoHoursMs }
        val hasRecentTemp = recentVitals.any { it.type == VitalType.TEMPERATURE_C && (nowMillis - it.measuredAtMillis) <= twoHoursMs }

        val needsBpCheck = lower.contains("голов") || lower.contains("мигрен") || lower.contains("круж") ||
            lower.contains("давлен") || lower.contains("тошн") || lower.contains("слабост") || lower.contains("давит") ||
            lower.contains("челюст") || lower.contains("серд") || lower.contains("руку") ||
            lower.contains("немеет") || lower.contains("пульс")

        if (needsBpCheck && !hasRecentBp) {
            suggestions.add(
                ActionSuggestion(
                    key = "measure_bp",
                    titleRu = "Померяйте сейчас давление и пульс",
                    reasonRu = "За последние 2 часа нет замера АД. Сравнение ощущений с цифрами тонометра помогает отличить скачок давления от мигрени или перегрузки."
                )
            )
            suggestions.add(
                ActionSuggestion(
                    key = "photo_tonometer",
                    titleRu = "Сфотографировать экран тонометра",
                    reasonRu = "Наведите камеру на экран прибора — цифры сохранятся как замер с прибора."
                )
            )
        }

        if ((lower.contains("озноб") || lower.contains("ломит") || lower.contains("жар") || lower.contains("слабост")) && !hasRecentTemp) {
            suggestions.add(
                ActionSuggestion(
                    key = "measure_temp",
                    titleRu = "Проверьте температуру тела",
                    reasonRu = "При слабости и ознобе важно исключить инфекционный фон."
                )
            )
        }
        return suggestions
    }

    fun buildMissingClarifyingQuestions(
        event: HealthEvent,
        lowerText: String,
        priorEvents: List<HealthEvent> = emptyList()
    ): List<ClarifyingQuestion> {
        if (event.category != HealthCategory.SYMPTOM && event.category != HealthCategory.GI_DIGESTION) {
            return emptyList()
        }
        val questions = mutableListOf<ClarifyingQuestion>()

        val hasSimilarBefore = priorEvents.any { prev ->
            prev.id != event.id && (
                (event.bodyRegion.isNotBlank() && prev.bodyRegion.equals(event.bodyRegion, ignoreCase = true)) ||
                    (lowerText.contains("мизинец") && prev.notes.contains("мизинец", ignoreCase = true)) ||
                    (lowerText.contains("немеет") && prev.notes.contains("немеет", ignoreCase = true))
                )
        }
        if (hasSimilarBefore) {
            questions.add(
                ClarifyingQuestion(
                    key = "chronic_vs_acute",
                    promptRu = "Это повторяется как обычно или резко сильнее?",
                    quickOptions = listOf("Хронически как обычно", "Заметно сильнее сегодня", "Резко и впервые так")
                )
            )
        }

        if (event.startedAtMillis == null) {
            questions.add(
                ClarifyingQuestion(
                    key = "onset",
                    promptRu = "Когда началось?",
                    quickOptions = listOf("Только что", "Утром", "1–3 ч назад", "Со вчера")
                )
            )
        }
        if (event.bodyRegion.isBlank()) {
            questions.add(
                ClarifyingQuestion(
                    key = "region",
                    promptRu = "Где именно ощущается?",
                    quickOptions = listOf("Виски / лоб", "Ниже горла / челюсть", "Грудная клетка", "Левая рука / пальцы", "Живот")
                )
            )
        }
        if (event.severity0to10 == null) {
            questions.add(
                ClarifyingQuestion(
                    key = "severity",
                    promptRu = "Интенсивность (0–10)?",
                    quickOptions = listOf("2 (слабо)", "4 (умеренно)", "6 (заметно)", "8 (сильно)")
                )
            )
        }
        if (event.painCharacter.isBlank() && event.category == HealthCategory.SYMPTOM) {
            questions.add(
                ClarifyingQuestion(
                    key = "character",
                    promptRu = "Какой характер?",
                    quickOptions = listOf("Пульсирующая", "Давящая / сжимает", "Онемение / холод", "Ноющая / тянет")
                )
            )
        }
        val mentionsPreceding = lowerText.contains("кофе") || lowerText.contains("спал") ||
            lowerText.contains("еда") || lowerText.contains("ел") || lowerText.contains("стресс") ||
            lowerText.contains("вино") || lowerText.contains("погод")
        if (!mentionsPreceding) {
            questions.add(
                ClarifyingQuestion(
                    key = "triggers",
                    promptRu = "Что было накануне?",
                    quickOptions = listOf("Недосып (<6ч)", "Много кофе", "Стресс", "Плотная/острая еда", "Смена погоды")
                )
            )
        }
        return questions.take(4)
    }

    private fun detectBodyRegion(lower: String, tags: Set<String>): String = when {
        lower.contains("виск") -> "Виски"
        lower.contains("лоб") || lower.contains("над бров") -> "Лоб"
        lower.contains("затыл") -> "Затылок"
        lower.contains("челюст") || lower.contains("ниже горла") -> "Горло / нижняя челюсть"
        lower.contains("мизинец") || lower.contains("пальц") || lower.contains("руку") || lower.contains("рука") -> "Рука / пальцы"
        lower.contains("голов") || lower.contains("мигрен") || tags.contains("Мигрень / Голова") -> "Голова"
        lower.contains("груд") || lower.contains("серд") -> "Грудная клетка"
        lower.contains("желуд") || lower.contains("изжог") -> "Верх живота / желудок"
        lower.contains("живот") -> "Живот"
        lower.contains("спин") || lower.contains("поясниц") -> "Спина"
        lower.contains("глаз") -> "Глаза"
        else -> ""
    }

    private fun detectPainCharacter(lower: String): String = when {
        lower.contains("пульсир") -> "Пульсирующая"
        lower.contains("давит") || lower.contains("давящ") || lower.contains("сжим") || lower.contains("душ") -> "Давящая / сжимающая"
        lower.contains("немеет") || lower.contains("онемен") || lower.contains("холодн") -> "Онемение / холод"
        lower.contains("колет") || lower.contains("колющ") -> "Колющая"
        lower.contains("ноет") || lower.contains("ноящ") || lower.contains("тянет") || lower.contains("туп") -> "Ноющая / тянущая"
        lower.contains("жжет") || lower.contains("жжёт") || lower.contains("жжен") || lower.contains("изжог") -> "Жжение"
        else -> ""
    }

    private fun inferStartedAtMillis(lower: String, nowMillis: Long): Long? = when {
        lower.contains("только что") || lower.contains("сейчас") -> nowMillis
        lower.contains("час назад") -> nowMillis - 3_600_000L
        lower.contains("утром") -> nowMillis - 4 * 3_600_000L
        lower.contains("вчера") -> nowMillis - 24 * 3_600_000L
        else -> null
    }

    private fun extractAlcoholName(lower: String): String = when {
        lower.contains("вин") -> "Вино"
        lower.contains("пив") -> "Пиво"
        lower.contains("коньяк") || lower.contains("виск") || lower.contains("водк") -> "Крепкий алкоголь"
        else -> "Алкоголь"
    }

    private fun inferTags(lower: String): List<String> {
        val list = mutableListOf<String>()
        if (lower.contains("мигрен") || lower.contains("голов")) list.add("Мигрень / Голова")
        if (lower.contains("давлен") || lower.contains("гипертон")) list.add("Давление")
        if (lower.contains("кофе")) list.add("Кофе")
        if (lower.contains("недосып") || lower.contains("плохо спал") || lower.contains("мало спал")) list.add("Недосып")
        if (lower.contains("стресс") || lower.contains("нерв")) list.add("Стресс")
        if (lower.contains("аллерг") || lower.contains("чиха") || lower.contains("зуд")) list.add("Аллергия")
        if (lower.contains("изжог") || lower.contains("живот") || lower.contains("стул") || lower.contains("запор") || lower.contains("понос")) list.add("Изжога/ЖКТ")
        if (lower.contains("алкогол") || lower.contains("вин") || lower.contains("пив")) list.add("Алкоголь")
        if (lower.contains("тошн")) list.add("Тошнота")
        if (lower.contains("немеет") || lower.contains("мизинец") || lower.contains("руку")) list.add("Онемение / рука")
        return list
    }

    private fun buildReadableTitle(text: String, tags: Set<String>, region: String): String {
        if (text.isNotBlank()) {
            return text.replaceFirstChar { it.uppercase() }.take(75)
        }
        val tagSummary = tags.joinToString(" · ")
        return when {
            tagSummary.isNotBlank() -> tagSummary
            region.isNotBlank() -> "Дискомфорт: $region"
            else -> "Запись о самочувствии"
        }
    }

    private fun formatDouble(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)

    private val SYMPTOM_TAGS = setOf(
        "Мигрень / Голова", "Давление", "Изжога/ЖКТ", "Аллергия", "Усталость", "Стресс", "Недосып"
    )
}
