package com.myhealth.ai.logic

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.myhealth.ai.model.CorrelationInsight
import com.myhealth.ai.model.HealthEvent
import com.myhealth.ai.model.HealthProfile
import com.myhealth.ai.model.IntakeEntry
import com.myhealth.ai.model.LabMarker
import com.myhealth.ai.model.MedicalDocument
import com.myhealth.ai.model.ReportLanguage
import com.myhealth.ai.model.VitalMeasurement
import com.myhealth.ai.model.VitalType
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a multi-page A4 clinical summary PDF in Russian (RU) or German (DE)
 * for doctor consultations, clearly separating user logs, device measurements, labs, and correlations.
 */
object DoctorReportPdfGenerator {

    fun generateReportPdf(
        context: Context,
        profile: HealthProfile,
        lang: ReportLanguage,
        daysBack: Int,
        events: List<HealthEvent>,
        vitals: List<VitalMeasurement>,
        intakes: List<IntakeEntry>,
        docs: List<MedicalDocument>,
        labs: List<LabMarker>,
        correlations: List<CorrelationInsight>,
        aiBriefText: String? = null
    ): File {
        val reportsDir = File(context.filesDir, "reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val outFile = File(reportsDir, "MyHealth_${lang.code}_$stamp.pdf")

        val cutoff = System.currentTimeMillis() - daysBack * 86_400_000L
        val filtEvents = events.filter { it.timestampMillis >= cutoff }.take(40)
        val filtVitals = vitals.filter { it.measuredAtMillis >= cutoff }
        val filtIntakes = intakes.filter { it.timestampMillis >= cutoff }
        val dtFmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)

        val pdf = PdfDocument()
        val pageW = 595
        val pageH = 842
        val margin = 42f
        val maxTextW = pageW - margin * 2

        val titlePaint = Paint().apply {
            color = Color.rgb(18, 52, 86)
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val headingPaint = Paint().apply {
            color = Color.rgb(25, 84, 120)
            textSize = 12.5f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = Paint().apply {
            color = Color.BLACK
            textSize = 10f
            typeface = Typeface.DEFAULT
        }
        val mutedPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 8.5f
            typeface = Typeface.DEFAULT
        }

        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
        var y = margin + 10f

        fun newPageIfNeeded(requiredHeight: Float) {
            if (y + requiredHeight > pageH - margin) {
                pdf.finishPage(page)
                pageNum++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                y = margin + 10f
            }
        }

        fun drawWrapped(text: String, paint: Paint, lineHeight: Float = 14f) {
            text.split('\n').forEach { para ->
                val words = para.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (words.isEmpty()) {
                    y += lineHeight * 0.6f
                    return@forEach
                }
                val line = StringBuilder()
                for (w in words) {
                    val candidate = if (line.isEmpty()) w else "$line $w"
                    if (paint.measureText(candidate) > maxTextW) {
                        newPageIfNeeded(lineHeight)
                        page.canvas.drawText(line.toString(), margin, y, paint)
                        y += lineHeight
                        line.clear().append(w)
                    } else {
                        if (line.isNotEmpty()) line.append(' ')
                        line.append(w)
                    }
                }
                if (line.isNotEmpty()) {
                    newPageIfNeeded(lineHeight)
                    page.canvas.drawText(line.toString(), margin, y, paint)
                    y += lineHeight
                }
            }
        }

        fun drawSectionTitle(title: String) {
            y += 8f
            newPageIfNeeded(24f)
            page.canvas.drawText(title, margin, y, headingPaint)
            y += 16f
        }

        val isDe = lang == ReportLanguage.DE
        drawWrapped(
            if (isDe) "GESUNDHEITSPROTOKOLL FÜR DEN ARZTBESUCH (${daysBack} Tage)"
            else "МЕДИЦИНСКАЯ ВЫПИСКА ДЛЯ ВРАЧА (за $daysBack дн.)",
            titlePaint,
            20f
        )
        drawWrapped(
            (if (isDe) "Erstellt: " else "Сформировано: ") + dtFmt.format(Date()) +
                "  |  " + (if (isDe) "Profil: " else "Профиль: ") + profile.name +
                (profile.birthYear?.let { if (isDe) " (Jg. $it)" else " ($it г.р.)" } ?: "") +
                (profile.phone.takeIf { it.isNotBlank() }?.let { "  |  Tel: $it" } ?: ""),
            mutedPaint,
            13f
        )
        drawWrapped(
            "${AppMeta.APP_EN_NAME} (${AppMeta.APP_NAME}) v${AppMeta.installedVersionName(context)}  |  " +
                (if (isDe) "Entwickler: " else "Разработчик: ") + AppMeta.DEVELOPER,
            mutedPaint,
            12f
        )
        drawWrapped(
            if (isDe) "Hinweis: Dieses Dokument bündelt Nutzereinträge, Gerätemessungen und statistische Hinweise. Es ersetzt keine ärztliche Diagnose."
            else "Примечание: отчёт объединяет записи пользователя, замеры приборов и статистические наблюдения. Не является медицинским диагнозом.",
            mutedPaint,
            12f
        )

        drawSectionTitle(if (isDe) "1. Basisdaten, Medikation & Allergien" else "1. Анамнез, лекарства и аллергии")
        drawWrapped(
            buildString {
                append(if (isDe) "Vorerkrankungen: " else "Хронические состояния: ")
                append(profile.chronicConditions.ifBlank { "-" })
                append("\n")
                append(if (isDe) "Aktuelle Medikamente: " else "Текущие лекарства: ")
                append(profile.medications.ifBlank { "-" })
                append("\n")
                append(if (isDe) "Allergien: " else "Аллергии: ")
                append(profile.allergies.ifBlank { "-" })
            },
            bodyPaint
        )

        drawSectionTitle(if (isDe) "2. Objektive Messwerte (Zusammenfassung)" else "2. Сводка объективных замеров")
        val bps = filtVitals.filter { it.type == VitalType.BLOOD_PRESSURE }
        val hrs = filtVitals.filter { it.type == VitalType.HEART_RATE }
        val sleeps = filtVitals.filter { it.type == VitalType.SLEEP_HOURS }
        val summaryLines = buildString {
            if (bps.isNotEmpty()) {
                val avgSys = bps.map { it.value1 }.average().toInt()
                val avgDia = bps.mapNotNull { it.value2 }.average().toInt()
                val maxSys = bps.maxOf { it.value1 }.toInt()
                append(if (isDe) "• Blutdruck (${bps.size}x): Ø $avgSys/$avgDia mmHg, Max $maxSys\n" else "• Давление (${bps.size} замеров): среднее $avgSys/$avgDia мм рт.ст., макс. $maxSys\n")
            }
            if (hrs.isNotEmpty()) {
                val avgHr = hrs.map { it.value1 }.average().toInt()
                append(if (isDe) "• Puls (${hrs.size}x): Ø $avgHr/min\n" else "• Пульс (${hrs.size} замеров): средний $avgHr уд/мин\n")
            }
            if (sleeps.isNotEmpty()) {
                val avgSleep = String.format(Locale.US, "%.1f", sleeps.map { it.value1 }.average())
                append(if (isDe) "• Schlaf (${sleeps.size}x): Ø $avgSleep Std.\n" else "• Сон (${sleeps.size} ночей): средний $avgSleep ч\n")
            }
            if (bps.isEmpty() && hrs.isEmpty() && sleeps.isEmpty()) {
                append(if (isDe) "Keine Messwerte im Zeitraum." else "Нет замеров за выбранный период.")
            }
        }
        drawWrapped(summaryLines, bodyPaint)

        drawSectionTitle(if (isDe) "3. Chronologie der Symptome & Ereignisse" else "3. Хронология симптомов и событий")
        if (filtEvents.isEmpty()) {
            drawWrapped(if (isDe) "Keine Einträge." else "Записей за период нет.", bodyPaint)
        } else {
            filtEvents.forEach { ev ->
                val sevStr = ev.severity0to10?.let { " [$it/10]" } ?: ""
                val src = if (isDe) ev.sourceTrust.badgeDe else ev.sourceTrust.badgeRu
                val line = "• ${dtFmt.format(Date(ev.timestampMillis))} — ${ev.title}$sevStr ($src)" +
                    if (ev.clarifyingQaSummary.isNotBlank()) " | ${ev.clarifyingQaSummary}" else ""
                drawWrapped(line, bodyPaint, 13f)
            }
        }

        drawSectionTitle(if (isDe) "4. Gewohnheiten / Zufuhr (Auswahl)" else "4. Питание, кофеин, алкоголь и лекарства")
        if (filtIntakes.isEmpty()) {
            drawWrapped(if (isDe) "Keine Einträge." else "Записей за период нет.", bodyPaint)
        } else {
            val grouped = filtIntakes.groupBy { it.kind }
            grouped.forEach { (kind, list) ->
                drawWrapped("• ${kind.titleRu}: ${list.size}x (${list.take(4).joinToString { it.name }})", bodyPaint, 13f)
            }
        }

        drawSectionTitle(if (isDe) "5. Statistische Muster (keine Kausalitätsbestätigung)" else "5. Статистические закономерности (не диагноз)")
        if (correlations.isEmpty()) {
            drawWrapped(if (isDe) "Noch zu wenig Daten für Muster." else "Пока недостаточно данных для устойчивой статистики.", bodyPaint)
        } else {
            correlations.take(6).forEach { c ->
                drawWrapped("• ${c.summaryText} [${c.confidenceLabel}]", bodyPaint, 13f)
            }
        }

        drawSectionTitle(if (isDe) "6. Dokumente & Laborwerte" else "6. Загруженные документы и анализы")
        if (docs.isEmpty() && labs.isEmpty()) {
            drawWrapped(if (isDe) "Keine Dokumente vorhanden." else "Документы пока не загружены.", bodyPaint)
        } else {
            docs.take(10).forEach { d ->
                drawWrapped("• ${dtFmt.format(Date(d.documentDateMillis))} — ${d.title}: ${d.extractedSummary.take(160)}", bodyPaint, 13f)
            }
            CorrelationEngine.summarizeLabTrends(labs).take(12).forEach { lt ->
                drawWrapped("  - ${lt.markerName}: ${lt.latestValue} ${lt.unit} (${lt.flag.titleRu}, ${lt.deltaText})", bodyPaint, 13f)
            }
        }

        if (!aiBriefText.isNullOrBlank()) {
            drawSectionTitle(if (isDe) "7. Strukturierte Fragen / KI-Zusammenfassung" else "7. Сводка и вопросы врачу (ИИ-помощник)")
            drawWrapped(aiBriefText, bodyPaint, 13f)
        }

        pdf.finishPage(page)
        FileOutputStream(outFile).use { fos -> pdf.writeTo(fos) }
        pdf.close()
        return outFile
    }
}
