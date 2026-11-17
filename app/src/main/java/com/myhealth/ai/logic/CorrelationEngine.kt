package com.myhealth.ai.logic

import com.myhealth.ai.model.CorrelationInsight
import com.myhealth.ai.model.HealthCategory
import com.myhealth.ai.model.HealthEvent
import com.myhealth.ai.model.IntakeEntry
import com.myhealth.ai.model.IntakeKind
import com.myhealth.ai.model.LabMarker
import com.myhealth.ai.model.LabTrendSummary
import com.myhealth.ai.model.RecommendationTier
import com.myhealth.ai.model.VitalMeasurement
import com.myhealth.ai.model.VitalType
import com.myhealth.ai.model.WeatherSnapshot
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Computes transparent time-lagged statistical associations without overclaiming causality.
 * Always reports sample count, baseline frequency, confidence tier, and alternative explanations.
 */
object CorrelationEngine {

    private const val HOUR_MS = 3_600_000L

    fun analyzePatterns(
        events: List<HealthEvent>,
        vitals: List<VitalMeasurement>,
        intakes: List<IntakeEntry>,
        weatherHistory: List<WeatherSnapshot>
    ): List<CorrelationInsight> {
        val weatherById = weatherHistory.associateBy { it.id }
        val insights = mutableListOf<CorrelationInsight>()

        val headacheEvents = events.filter {
            (it.category == HealthCategory.SYMPTOM) && (
                it.title.contains("голов", ignoreCase = true) ||
                    it.title.contains("мигрен", ignoreCase = true) ||
                    it.bodyRegion.contains("Голов", ignoreCase = true) ||
                    it.bodyRegion.contains("Виск", ignoreCase = true) ||
                    it.tags.any { t -> t.contains("Мигрень", ignoreCase = true) || t.contains("Голова", ignoreCase = true) }
                )
        }

        if (headacheEvents.size >= 2) {
            // 1. Sleep < 6h before headache (within 24h)
            val sleepBaselinePct = computeVitalBaselinePct(vitals, VitalType.SLEEP_HOURS) { it.value1 < 6.0 }
            checkWindowFactor(
                id = "headache_sleep",
                symptomLabel = "Головная боль / мигрень",
                factorLabel = "сон меньше 6 часов",
                windowHours = 24,
                symptomEvents = headacheEvents,
                baselinePct = sleepBaselinePct,
                alternative = "Недосып часто сопровождается стрессом, обезвоживанием или поздним кофеином — отслеживайте их вместе.",
                predicate = { ev ->
                    vitals.any { v ->
                        v.type == VitalType.SLEEP_HOURS && v.value1 < 6.0 &&
                            v.measuredAtMillis in (ev.timestampMillis - 24 * HOUR_MS)..ev.timestampMillis
                    } || ev.tags.any { it.contains("Недосып", ignoreCase = true) }
                }
            )?.let { insights.add(it) }

            // 2. Atmospheric pressure drop (<= -4 hPa in 24h or <= -3 hPa in 6h)
            val pressureBaselinePct = if (weatherHistory.isNotEmpty()) {
                (weatherHistory.count { it.pressureDelta24hHpa <= -4.0 || abs(it.pressureDelta6hHpa) >= 3.5 } * 100.0 / weatherHistory.size).roundToInt()
            } else 0
            checkWindowFactor(
                id = "headache_pressure",
                symptomLabel = "Головная боль / мигрень",
                factorLabel = "резкий перепад атмосферного давления",
                windowHours = 24,
                symptomEvents = headacheEvents,
                baselinePct = pressureBaselinePct,
                alternative = "Реакция на перепады давления может усиливаться на фоне недосыпа или недостатка воды.",
                predicate = { ev ->
                    val w = ev.weatherSnapshotId?.let { weatherById[it] }
                        ?: nearestWeather(weatherHistory, ev.timestampMillis, 6 * HOUR_MS)
                    w != null && (w.pressureDelta24hHpa <= -4.0 || abs(w.pressureDelta6hHpa) >= 3.5)
                }
            )?.let { insights.add(it) }

            // 3. Alcohol in prior 24h
            val alcoholBaselinePct = if (events.isNotEmpty()) {
                val daysWithAlc = intakes.filter { it.kind == IntakeKind.ALCOHOL }.map { it.timestampMillis / (24 * HOUR_MS) }.distinct().size
                val totalDays = (events.map { it.timestampMillis / (24 * HOUR_MS) }.distinct().size).coerceAtLeast(1)
                ((daysWithAlc * 100.0) / totalDays).roundToInt().coerceAtMost(100)
            } else 0
            checkWindowFactor(
                id = "headache_alcohol",
                symptomLabel = "Головная боль / мигрень",
                factorLabel = "алкоголь за предыдущие 24 часа",
                windowHours = 24,
                symptomEvents = headacheEvents,
                baselinePct = alcoholBaselinePct,
                alternative = "Триггером может быть не только сам этанол, но и обезвоживание или гистамин/сульфиты в напитке.",
                predicate = { ev ->
                    intakes.any { i ->
                        i.kind == IntakeKind.ALCOHOL &&
                            i.timestampMillis in (ev.timestampMillis - 24 * HOUR_MS)..ev.timestampMillis
                    } || ev.tags.any { it.contains("Алкоголь", ignoreCase = true) }
                }
            )?.let { insights.add(it) }
        }

        // 4. GI / Heartburn vs Coffee (>= 2 servings or caffeine within 8h)
        val giEvents = events.filter {
            it.category == HealthCategory.GI_DIGESTION ||
                it.title.contains("изжог", ignoreCase = true) ||
                it.tags.any { t -> t.contains("ЖКТ", ignoreCase = true) || t.contains("Изжог", ignoreCase = true) }
        }
        if (giEvents.size >= 2) {
            checkWindowFactor(
                id = "gi_coffee",
                symptomLabel = "Изжога / дискомфорт ЖКТ",
                factorLabel = "2+ порций кофе или кофеин за 8 ч до симптома",
                windowHours = 8,
                symptomEvents = giEvents,
                baselinePct = 0,
                alternative = "Также проверьте сочетание с выпечкой, молочными продуктами, острой пищей или поздним ужином.",
                predicate = { ev ->
                    val coffees = intakes.filter { i ->
                        (i.kind == IntakeKind.COFFEE || i.kind == IntakeKind.CAFFEINE_PILL) &&
                            i.timestampMillis in (ev.timestampMillis - 8 * HOUR_MS)..ev.timestampMillis
                    }
                    val totalCups = coffees.sumOf { it.amountValue ?: 1.0 }
                    totalCups >= 2.0 || ev.tags.any { it.contains("Кофе", ignoreCase = true) }
                }
            )?.let { insights.add(it) }
        }

        // 5. High BP (>= 140/90) vs Stress / Short sleep / Alcohol
        val highBpTimes = vitals.filter { it.type == VitalType.BLOOD_PRESSURE && (it.value1 >= 140.0 || (it.value2 ?: 0.0) >= 90.0) }
        if (highBpTimes.size >= 2) {
            val matchedStressOrSleep = highBpTimes.count { bp ->
                events.any { ev ->
                    ev.timestampMillis in (bp.measuredAtMillis - 24 * HOUR_MS)..bp.measuredAtMillis &&
                        ev.tags.any { it.contains("Стресс", ignoreCase = true) || it.contains("Недосып", ignoreCase = true) }
                } || vitals.any { v ->
                    v.type == VitalType.SLEEP_HOURS && v.value1 < 6.0 &&
                        v.measuredAtMillis in (bp.measuredAtMillis - 24 * HOUR_MS)..bp.measuredAtMillis
                }
            }
            if (matchedStressOrSleep > 0) {
                val pct = ((matchedStressOrSleep * 100.0) / highBpTimes.size).roundToInt()
                insights.add(
                    CorrelationInsight(
                        id = "bp_stress_sleep",
                        symptomLabel = "Повышенное давление (≥140/90)",
                        factorLabel = "стресс или недосып за 24 часа",
                        windowHours = 24,
                        coOccurrencePct = pct,
                        matchedCount = matchedStressOrSleep,
                        totalSymptomCount = highBpTimes.size,
                        baselineFactorPct = 0,
                        confidenceLabel = confidenceText(highBpTimes.size),
                        tier = if (highBpTimes.size >= 5 && pct >= 60) RecommendationTier.SCHEDULE_DOCTOR else RecommendationTier.OBSERVATION,
                        summaryText = "В $pct% замеров повышенного АД ($matchedStressOrSleep из ${highBpTimes.size}) за сутки до замера отмечались стресс или сон <6 ч.",
                        alternativeExplanations = "На давление также влияют соль в рационе, кофеин, время суток и техника измерения в покое."
                    )
                )
            }
        }

        return insights.sortedByDescending { it.coOccurrencePct }
    }

    fun summarizeLabTrends(markers: List<LabMarker>): List<LabTrendSummary> {
        return markers
            .groupBy { it.markerName.trim().lowercase(Locale.ROOT) }
            .values
            .mapNotNull { group ->
                val sorted = group.sortedByDescending { it.measuredAtMillis }
                val latest = sorted.firstOrNull() ?: return@mapNotNull null
                val prev = sorted.getOrNull(1)
                val deltaText = if (prev != null) {
                    val diff = latest.value - prev.value
                    val sign = if (diff >= 0) "+" else ""
                    "$sign${String.format(Locale.US, "%.2f", diff)} (было ${String.format(Locale.US, "%.2f", prev.value)})"
                } else {
                    "Первый сохранённый замер"
                }
                LabTrendSummary(
                    markerName = latest.markerName,
                    latestValue = latest.value,
                    previousValue = prev?.value,
                    unit = latest.unit,
                    latestDateMillis = latest.measuredAtMillis,
                    flag = latest.flag,
                    deltaText = deltaText,
                    count = sorted.size
                )
            }
            .sortedByDescending { it.latestDateMillis }
    }

    private fun checkWindowFactor(
        id: String,
        symptomLabel: String,
        factorLabel: String,
        windowHours: Int,
        symptomEvents: List<HealthEvent>,
        baselinePct: Int,
        alternative: String,
        predicate: (HealthEvent) -> Boolean
    ): CorrelationInsight? {
        val total = symptomEvents.size
        val matched = symptomEvents.count(predicate)
        if (matched == 0) return null
        val pct = ((matched * 100.0) / total).roundToInt()
        if (pct < 25 && matched < 2) return null

        val tier = when {
            total >= 5 && pct >= 60 -> RecommendationTier.LIFESTYLE
            else -> RecommendationTier.OBSERVATION
        }
        return CorrelationInsight(
            id = id,
            symptomLabel = symptomLabel,
            factorLabel = factorLabel,
            windowHours = windowHours,
            coOccurrencePct = pct,
            matchedCount = matched,
            totalSymptomCount = total,
            baselineFactorPct = baselinePct,
            confidenceLabel = confidenceText(total),
            tier = tier,
            summaryText = "В $pct% случаев ($matched из $total) «$symptomLabel» совпадало с фактором «$factorLabel» (окно $windowHours ч).",
            alternativeExplanations = alternative
        )
    }

    private fun computeVitalBaselinePct(
        vitals: List<VitalMeasurement>,
        type: VitalType,
        cond: (VitalMeasurement) -> Boolean
    ): Int {
        val list = vitals.filter { it.type == type }
        if (list.isEmpty()) return 0
        return ((list.count(cond) * 100.0) / list.size).roundToInt()
    }

    private fun nearestWeather(list: List<WeatherSnapshot>, targetMillis: Long, maxDiffMs: Long): WeatherSnapshot? {
        return list
            .minByOrNull { abs(it.timestampMillis - targetMillis) }
            ?.takeIf { abs(it.timestampMillis - targetMillis) <= maxDiffMs }
    }

    private fun confidenceText(sampleSize: Int): String = when {
        sampleSize < 5 -> "Низкая уверенность (мало данных: $sampleSize; нужно ≥ 5–10 записей)"
        sampleSize < 15 -> "Предварительная связь ($sampleSize наблюдений — продолжайте дневник)"
        else -> "Умеренная статистическая связь ($sampleSize наблюдений, не равна диагнозу)"
    }
}
