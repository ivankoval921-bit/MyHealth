package com.myhealth.ai.logic

import com.myhealth.ai.model.HealthEvent
import com.myhealth.ai.model.HealthProfile
import com.myhealth.ai.model.RecommendationTier
import com.myhealth.ai.model.RedFlagAssessment
import com.myhealth.ai.model.ReportLanguage
import com.myhealth.ai.model.VitalMeasurement
import com.myhealth.ai.model.VitalType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Evaluates symptoms and objective vitals for urgent warning signs ("red flags")
 * while distinguishing chronic recurring peripheral symptoms from acute onset.
 */
object RedFlagSafetyEngine {

    private val chestCardiacTriggers = listOf(
        "давит в груди", "давящая боль в груди", "сжимает грудь", "жжение за грудиной",
        "боль за грудиной", "отдает в левую руку", "отдаёт в левую руку", "отдает в челюсть",
        "отдаёт в челюсть", "ниже горла давит", "давит ниже горла", "под челюстью давит",
        "под челюстью сжимает", "душит под челюстью", "сдавливает под нижней челюстью",
        "не хватает воздуха", "задыхаюсь", "тяжело дышать", "тяжеловато дышать",
        "brustschmerz", "atemnot", "прединфаркт", "острая боль в сердце", "очень тяжело дышать"
    )

    private val neuroStrokeTriggers = listOf(
        "перекосило лицо", "онемела половина", "немеет лицо",
        "заплетается язык", "нарушилась речь", "двоится в глазах", "резко потерял зрение",
        "громоподобная головная боль", "нестерпимая головная боль", "потерял сознание",
        "падаю в обморок", "судороги", "bewusstlos", "sprachstörung", "lähmung",
        "микроинсульт", "инсульт"
    )

    private val peripheralArmOrFingerTriggers = listOf(
        "немеет рука", "онемела рука", "тянет левую руку", "плохо чувствую руку",
        "немеют пальцы", "холодный мизинец", "плохая чувствительность"
    )

    private val acuteMarkers = listOf(
        "резко", "внезапно", "впервые", "сильнее обычного", "хуже обычного",
        "не могу пошевелить", "прямо сейчас началось", "очень резко", "plötzlich"
    )

    private val severeAllergyTriggers = listOf(
        "отек горла", "отёк горла", "отек квинке", "отёк квинке", "отекает язык",
        "не могу глотать", "свистящее дыхание", "анафилакси"
    )

    private val severeGiOrBleedingTriggers = listOf(
        "рвота кровью", "черный стул", "чёрный стул", "кровь в стуле",
        "кинжальная боль в животе", "нестерпимая боль в животе"
    )

    fun evaluate(
        text: String,
        severity0to10: Int? = null,
        latestVitals: List<VitalMeasurement> = emptyList(),
        priorEvents: List<HealthEvent> = emptyList(),
        nowMillis: Long = System.currentTimeMillis()
    ): RedFlagAssessment {
        val lower = text.lowercase()

        if (neuroStrokeTriggers.any { lower.contains(it) }) {
            return RedFlagAssessment(
                isUrgent = true,
                tier = RecommendationTier.URGENT,
                headline = "Возможные острые неврологические симптомы",
                reason = "Упомянуты признаки, требующие немедленной очной оценки (нарушение речи/зрения, онемение лица/половины тела, обморок или внезапная нестерпимая боль).",
                actions = listOf(
                    "Немедленно позвоните 112 (или включите Таймер контроля и оповестите близкого).",
                    "Не садитесь за руль самостоятельно и зафиксируйте точное время начала симптомов.",
                    "Если есть возможность — измерьте давление и пульс в покое."
                ),
                recommendNotifyContact = true
            )
        }

        if (severeAllergyTriggers.any { lower.contains(it) }) {
            return RedFlagAssessment(
                isUrgent = true,
                tier = RecommendationTier.URGENT,
                headline = "Признаки тяжёлой аллергической реакции / отёка дыхательных путей",
                reason = "Упомянут отёк горла/языка или острое затруднение глотания/дыхания.",
                actions = listOf(
                    "Немедленно вызовите скорую помощь (112).",
                    "Если врач ранее выписывал вам экстренный препарат — действуйте по назначенному плану."
                ),
                recommendNotifyContact = true
            )
        }

        val hasThroatOrJawPressure = (lower.contains("горл") || lower.contains("челюст")) &&
            (lower.contains("давит") || lower.contains("сдав") || lower.contains("сжим") || lower.contains("душ"))
        val hasChest = lower.contains("груд") || lower.contains("сердц") || lower.contains("brust")
        val hasDyspneaOrArm = lower.contains("одышк") || lower.contains("задых") ||
            lower.contains("дышать") || lower.contains("левую руку") ||
            lower.contains("в руку") || lower.contains("холодный пот")

        if (chestCardiacTriggers.any { lower.contains(it) } || hasThroatOrJawPressure || ((hasChest || lower.contains("левую руку")) && hasDyspneaOrArm)) {
            return RedFlagAssessment(
                isUrgent = true,
                tier = RecommendationTier.URGENT,
                headline = "Возможные кардиологические симптомы (грудь / ниже горла / челюсть / одышка)",
                reason = "Сдавливание ниже горла или под нижней челюстью, боль в груди, одышка или иррадиация в левую руку требуют исключения острых проблем с сердцем.",
                actions = listOf(
                    "Прекратите любую нагрузку и сядьте в покое.",
                    "По возможности измерьте давление и пульс (или сфотографируйте экран тонометра).",
                    "При сохранении в покое — звоните 112 или включите Таймер контроля состояния."
                ),
                recommendNotifyContact = true
            )
        }

        if (severeGiOrBleedingTriggers.any { lower.contains(it) }) {
            return RedFlagAssessment(
                isUrgent = true,
                tier = RecommendationTier.URGENT,
                headline = "Тревожные симптомы со стороны ЖКТ / кровотечения",
                reason = "Острая нестерпимая боль в животе или признаки кровотечения требуют срочного врачебного осмотра.",
                actions = listOf(
                    "Не принимайте обезболивающие до осмотра врача при острой боли неясной причины.",
                    "Обратитесь в отделение неотложной помощи или вызовите 112."
                ),
                recommendNotifyContact = true
            )
        }

        // Chronic vs acute check for peripheral hand/finger numbness or cold pinky
        val hasPeripheralHandPhrase = peripheralArmOrFingerTriggers.any { lower.contains(it) } ||
            ((lower.contains("неме") || lower.contains("онеме") || lower.contains("чувств") || lower.contains("тянет") || lower.contains("слабост")) &&
                (lower.contains("рук") || lower.contains("мизин") || lower.contains("пальц")))
        if (hasPeripheralHandPhrase) {
            val similarHistory = priorEvents.filter { ev ->
                val eLower = (ev.title + " " + ev.notes).lowercase()
                peripheralArmOrFingerTriggers.any { eLower.contains(it) } ||
                    eLower.contains("мизинец") || eLower.contains("пальц") || eLower.contains("немеет")
            }
            val spansMultipleDays = similarHistory.any { nowMillis - it.timestampMillis > 24 * 3_600_000L }
            val hasAcuteWord = acuteMarkers.any { lower.contains(it) } || lower.contains("не могу двигать") || lower.contains("вся левая рука") || lower.contains("вся рука")
            val hasHeadacheOrSpeech = lower.contains("голов") || lower.contains("круж") || lower.contains("реч")

            if (similarHistory.size >= 2 && spansMultipleDays && !hasAcuteWord && !hasHeadacheOrSpeech) {
                return RedFlagAssessment(
                    isUrgent = false,
                    tier = RecommendationTier.SCHEDULE_DOCTOR,
                    headline = "Повторяющееся онемение / холод в руке или пальцах (в хронике ${similarHistory.size}x)",
                    reason = "Похожие жалобы уже встречались в вашей истории ранее. Регулярное проявление без резкого ухудшения реже бывает острым инсультом и чаще указывает на хронические причины (нервы/шея/сосуды), требующие плановой проверки.",
                    actions = listOf(
                        "Уточните: сегодня это так же, как обычно, или стало резко сильнее/поднялось выше?",
                        "Замерьте давление и запишите — обсудите динамику с неврологом или терапевтом."
                    ),
                    recommendNotifyContact = false,
                    isChronicContextNote = true
                )
            }

            return RedFlagAssessment(
                isUrgent = true,
                tier = RecommendationTier.URGENT,
                headline = "Онемение / слабость руки требует быстрой проверки",
                reason = "Если онемение руки, тянущая боль в левой руке или потеря чувствительности появились впервые или резко — нужно исключить острое нарушение кровообращения или сердца.",
                actions = listOf(
                    "Проверьте, симметрична ли улыбка, не нарушена ли речь, одинакова ли сила обеих рук.",
                    "Измерьте давление и пульс. При сомнении, слабости или нарастании симптома — звоните 112."
                ),
                recommendNotifyContact = true
            )
        }

        // Check objective vitals
        val bp = latestVitals.firstOrNull { it.type == VitalType.BLOOD_PRESSURE }
        if (bp != null && (bp.value1 >= 180.0 || (bp.value2 ?: 0.0) >= 120.0)) {
            val withSymptoms = (severity0to10 ?: 0) >= 5 || lower.contains("голов") || lower.contains("груд") || lower.contains("мушк")
            return RedFlagAssessment(
                isUrgent = true,
                tier = RecommendationTier.URGENT,
                headline = "Критически высокое давление (${bp.value1.toInt()}/${bp.value2?.toInt() ?: "?"} мм рт.ст.)",
                reason = if (withSymptoms) {
                    "Высокое давление сочетается с выраженными симптомами — возможен гипертонический криз."
                } else {
                    "Показатель >= 180/120 требует повторного контрольного замера в покое и срочной связи с врачом."
                },
                actions = listOf(
                    "Сделайте повторный замер через 5 минут в покое.",
                    "При сохранении >= 180/120 или боли в груди/голове/нарушении зрения — звоните 112."
                ),
                recommendNotifyContact = withSymptoms
            )
        }

        val spo2 = latestVitals.firstOrNull { it.type == VitalType.SPO2_PCT }
        if (spo2 != null && spo2.value1 < 90.0) {
            return RedFlagAssessment(
                isUrgent = true,
                tier = RecommendationTier.URGENT,
                headline = "Низкое насыщение крови кислородом (${spo2.value1.toInt()}%)",
                reason = "Значение SpO₂ ниже 90% может указывать на острую нехватку кислорода.",
                actions = listOf(
                    "Перемерьте на тёплом пальце в покое (холодные руки занижают показатель).",
                    "Если показатель подтверждается или есть одышка — срочно обратитесь за медицинской помощью (112)."
                ),
                recommendNotifyContact = true
            )
        }

        if ((severity0to10 ?: 0) >= 9) {
            return RedFlagAssessment(
                isUrgent = false,
                tier = RecommendationTier.SCHEDULE_DOCTOR,
                headline = "Выраженное ухудшение самочувствия ($severity0to10/10)",
                reason = "Очень высокая интенсивность симптома. При резком нарастании или новых тревожных признаках не ждите планового приёма.",
                actions = listOf(
                    "Зафиксируйте детали и проверьте давление/температуру.",
                    "Если состояние не снижается или усиливается — обратитесь в Notaufnahme / к врачу (116 117 или 112)."
                ),
                recommendNotifyContact = false
            )
        }

        return RedFlagAssessment(
            isUrgent = false,
            tier = RecommendationTier.OBSERVATION,
            headline = "Острых красных флагов не выявлено",
            reason = "Запись сохранена в хронику. Наблюдайте динамику и отмечайте возможные триггеры.",
            actions = emptyList(),
            recommendNotifyContact = false
        )
    }

    /**
     * Generates a structured emergency card in RU or DE for 112 dispatchers / TTS readout / trusted contact SMS.
     */
    fun buildEmergencyDispatcherCard(
        profile: HealthProfile?,
        events: List<HealthEvent>,
        vitals: List<VitalMeasurement>,
        gpsLocationText: String,
        lang: ReportLanguage,
        nowMillis: Long = System.currentTimeMillis()
    ): String {
        val dt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date(nowMillis))
        val name = profile?.name?.ifBlank { "Не указано" } ?: "Не указано"
        val ageText = profile?.birthYear?.let { by ->
            val approxAge = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) - by
            if (lang == ReportLanguage.DE) "$approxAge Jahre (Jahrgang $by)" else "$approxAge лет ($by г.р.)"
        } ?: if (lang == ReportLanguage.DE) "Alter unbekannt" else "возраст не указан"
        val address = profile?.homeAddress?.ifBlank { gpsLocationText } ?: gpsLocationText
        val recentSymptom = events.firstOrNull { it.timestampMillis >= nowMillis - 6 * 3_600_000L }?.title
            ?: events.firstOrNull()?.title ?: (if (lang == ReportLanguage.DE) "akutes Unwohlsein" else "резкое ухудшение самочувствия")
        val bp = vitals.firstOrNull { it.type == VitalType.BLOOD_PRESSURE }
        val hr = vitals.firstOrNull { it.type == VitalType.HEART_RATE }
        val spo2 = vitals.firstOrNull { it.type == VitalType.SPO2_PCT }

        val vitalsLine = buildList {
            bp?.let { add(if (lang == ReportLanguage.DE) "Blutdruck ${it.value1.toInt()} zu ${it.value2?.toInt() ?: 0}" else "АД ${it.value1.toInt()}/${it.value2?.toInt() ?: 0}") }
            hr?.let { add(if (lang == ReportLanguage.DE) "Puls ${it.value1.toInt()}" else "пульс ${it.value1.toInt()}") }
            spo2?.let { add("SpO2 ${it.value1.toInt()}%") }
        }.joinToString(", ").ifBlank { if (lang == ReportLanguage.DE) "keine aktuellen Messwerte" else "свежих замеров нет" }

        val meds = profile?.medications?.takeIf { it.isNotBlank() } ?: "-"
        val chronic = profile?.chronicConditions?.takeIf { it.isNotBlank() } ?: "-"
        val phoneNote = profile?.phone?.takeIf { it.isNotBlank() }?.let {
            if (lang == ReportLanguage.DE) " Tel.: $it." else " Тел.: $it."
        }.orEmpty()

        return if (lang == ReportLanguage.DE) {
            "NOTRUF-INFORMATION ($dt). Patient: $name, $ageText.$phoneNote Adresse / Standort: $address. " +
                "Aktuelle Beschwerden: $recentSymptom. Messwerte: $vitalsLine. " +
                "Vorerkrankungen: $chronic. Medikamente: $meds."
        } else {
            "ЭКСТРЕННАЯ КАРТОЧКА ($dt). Пациент: $name, $ageText.$phoneNote Адрес / координаты: $address. " +
                "Жалобы: $recentSymptom. Замеры: $vitalsLine. " +
                "Хронические болезни: $chronic. Лекарства: $meds."
        }
    }
}
