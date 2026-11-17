package com.myhealth.ai.data

import android.content.Context
import com.myhealth.ai.model.DataSourceTrust
import com.myhealth.ai.model.DocType
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
import com.myhealth.ai.model.VitalType
import com.myhealth.ai.model.WeatherSnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class BackupExportResult(
    val zipFile: File,
    val profileCount: Int,
    val totalRecords: Int,
    val attachmentCount: Int
)

data class BackupRestoreResult(
    val profileCount: Int,
    val restoredRecords: Int,
    val restoredAttachments: Int
)

object BackupManager {

    fun exportAllToZip(context: Context, db: HealthDatabase): BackupExportResult {
        val backupsDir = File(context.filesDir, "backups").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(backupsDir, "myhealth_backup_$stamp.zip")

        val profiles = db.getProfiles()
        val weather = db.getWeatherSnapshots(limit = 5000)
        val events = mutableListOf<HealthEvent>()
        val vitals = mutableListOf<VitalMeasurement>()
        val intakes = mutableListOf<IntakeEntry>()
        val docs = mutableListOf<MedicalDocument>()
        val labs = mutableListOf<LabMarker>()

        profiles.forEach { p ->
            events.addAll(db.getEventsForProfile(p.id, limit = 10000))
            vitals.addAll(db.getVitalsForProfile(p.id, limit = 10000))
            intakes.addAll(db.getIntakesForProfile(p.id, limit = 10000))
            docs.addAll(db.getDocumentsForProfile(p.id, limit = 5000))
            labs.addAll(db.getLabMarkersForProfile(p.id, limit = 10000))
        }

        val root = JSONObject().apply {
            put("schema_version", 1)
            put("exported_at_millis", System.currentTimeMillis())
            put("profiles", JSONArray().apply {
                profiles.forEach { p ->
                    put(JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        if (p.birthYear != null) put("birthYear", p.birthYear)
                        put("sex", p.sex)
                        put("phone", p.phone)
                        put("email", p.email)
                        put("homeAddress", p.homeAddress)
                        put("chronicConditions", p.chronicConditions)
                        put("medications", p.medications)
                        put("allergies", p.allergies)
                        put("emergencyContactName", p.emergencyContactName)
                        put("emergencyContactPhone", p.emergencyContactPhone)
                        put("autoSmsCheckInEnabled", p.autoSmsCheckInEnabled)
                        put("preferredReportLang", p.preferredReportLang.code)
                        put("createdAtMillis", p.createdAtMillis)
                    })
                }
            })
            put("weather_snapshots", JSONArray().apply {
                weather.forEach { w ->
                    put(JSONObject().apply {
                        put("id", w.id)
                        put("timestampMillis", w.timestampMillis)
                        put("latitude", w.latitude)
                        put("longitude", w.longitude)
                        put("locationName", w.locationName)
                        put("temperatureC", w.temperatureC)
                        put("humidityPct", w.humidityPct)
                        put("pressureHpa", w.pressureHpa)
                        put("pressureMmHg", w.pressureMmHg)
                        put("pressureDelta6hHpa", w.pressureDelta6hHpa)
                        put("pressureDelta24hHpa", w.pressureDelta24hHpa)
                        if (w.aqiEuropean != null) put("aqiEuropean", w.aqiEuropean)
                        if (w.pm25 != null) put("pm25", w.pm25)
                        if (w.kpIndex != null) put("kpIndex", w.kpIndex)
                        put("summaryText", w.summaryText)
                    })
                }
            })
            put("health_events", JSONArray().apply {
                events.forEach { e ->
                    put(JSONObject().apply {
                        put("id", e.id)
                        put("profileId", e.profileId)
                        put("timestampMillis", e.timestampMillis)
                        put("category", e.category.name)
                        put("title", e.title)
                        put("notes", e.notes)
                        if (e.severity0to10 != null) put("severity0to10", e.severity0to10)
                        put("bodyRegion", e.bodyRegion)
                        put("painCharacter", e.painCharacter)
                        if (e.startedAtMillis != null) put("startedAtMillis", e.startedAtMillis)
                        put("tags", JSONArray(e.tags))
                        put("sourceTrust", e.sourceTrust.name)
                        put("sourceDetail", e.sourceDetail)
                        if (e.weatherSnapshotId != null) put("weatherSnapshotId", e.weatherSnapshotId)
                        if (e.attachmentId != null) put("attachmentId", e.attachmentId)
                        put("isRedFlagTriggered", e.isRedFlagTriggered)
                        put("clarifyingQaSummary", e.clarifyingQaSummary)
                    })
                }
            })
            put("vital_measurements", JSONArray().apply {
                vitals.forEach { v ->
                    put(JSONObject().apply {
                        put("id", v.id)
                        put("profileId", v.profileId)
                        put("measuredAtMillis", v.measuredAtMillis)
                        put("type", v.type.name)
                        put("value1", v.value1)
                        if (v.value2 != null) put("value2", v.value2)
                        put("unit", v.unit)
                        put("contextTag", v.contextTag)
                        put("sourceTrust", v.sourceTrust.name)
                        put("sourceDetail", v.sourceDetail)
                        if (v.attachmentPath != null) put("attachmentFileName", File(v.attachmentPath).name)
                    })
                }
            })
            put("intake_entries", JSONArray().apply {
                intakes.forEach { i ->
                    put(JSONObject().apply {
                        put("id", i.id)
                        put("profileId", i.profileId)
                        put("timestampMillis", i.timestampMillis)
                        put("kind", i.kind.name)
                        put("name", i.name)
                        if (i.amountValue != null) put("amountValue", i.amountValue)
                        put("amountUnit", i.amountUnit)
                        if (i.estimatedKcal != null) put("estimatedKcal", i.estimatedKcal)
                        if (i.proteinG != null) put("proteinG", i.proteinG)
                        if (i.fatG != null) put("fatG", i.fatG)
                        if (i.carbsG != null) put("carbsG", i.carbsG)
                        if (i.photoPath != null) put("photoFileName", File(i.photoPath).name)
                        put("sourceTrust", i.sourceTrust.name)
                        put("notes", i.notes)
                    })
                }
            })
            put("medical_documents", JSONArray().apply {
                docs.forEach { d ->
                    put(JSONObject().apply {
                        put("id", d.id)
                        put("profileId", d.profileId)
                        put("documentDateMillis", d.documentDateMillis)
                        put("createdAtMillis", d.createdAtMillis)
                        put("docType", d.docType.name)
                        put("title", d.title)
                        put("doctorOrClinic", d.doctorOrClinic)
                        put("fileName", File(d.originalFilePath).name)
                        put("mimeType", d.mimeType)
                        put("ocrText", d.ocrText)
                        put("extractedSummary", d.extractedSummary)
                        put("userCorrected", d.userCorrected)
                        put("sourceTrust", d.sourceTrust.name)
                    })
                }
            })
            put("lab_markers", JSONArray().apply {
                labs.forEach { l ->
                    put(JSONObject().apply {
                        put("id", l.id)
                        put("documentId", l.documentId)
                        put("profileId", l.profileId)
                        put("measuredAtMillis", l.measuredAtMillis)
                        put("markerName", l.markerName)
                        put("value", l.value)
                        put("unit", l.unit)
                        if (l.refMin != null) put("refMin", l.refMin)
                        if (l.refMax != null) put("refMax", l.refMax)
                        put("flag", l.flag.name)
                        put("userVerified", l.userVerified)
                    })
                }
            })
        }

        var attachmentCount = 0
        val attachmentsDir = File(context.filesDir, "attachments")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            zos.putNextEntry(ZipEntry("myhealth_backup.json"))
            zos.write(root.toString(2).toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            if (attachmentsDir.isDirectory) {
                attachmentsDir.listFiles()?.forEach { file ->
                    if (file.isFile) {
                        zos.putNextEntry(ZipEntry("attachments/${file.name}"))
                        FileInputStream(file).use { fis -> fis.copyTo(zos) }
                        zos.closeEntry()
                        attachmentCount++
                    }
                }
            }
        }

        val totalRecords = events.size + vitals.size + intakes.size + docs.size + labs.size + weather.size
        db.logAudit("system", "BACKUP_EXPORT", "Экспорт ZIP: ${zipFile.name} ($totalRecords записей, $attachmentCount файлов)")
        return BackupExportResult(zipFile, profiles.size, totalRecords, attachmentCount)
    }

    fun restoreFromZipStream(context: Context, db: HealthDatabase, inputStream: InputStream): BackupRestoreResult {
        val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }
        var backupJsonText: String? = null
        var restoredAttachments = 0

        ZipInputStream(BufferedInputStream(inputStream)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val safeName = entry.name.replace('\\', '/')
                if (!entry.isDirectory && !safeName.contains("..")) {
                    if (safeName == "myhealth_backup.json") {
                        backupJsonText = zis.readBytes().toString(Charsets.UTF_8)
                    } else if (safeName.startsWith("attachments/")) {
                        val fileName = safeName.removePrefix("attachments/").substringAfterLast('/')
                        if (fileName.isNotBlank()) {
                            val outFile = File(attachmentsDir, fileName)
                            FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                            restoredAttachments++
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val jsonStr = backupJsonText ?: throw IllegalArgumentException("В архиве отсутствует myhealth_backup.json")
        val root = JSONObject(jsonStr)
        var restoredRecords = 0

        val profilesArr = root.optJSONArray("profiles") ?: JSONArray()
        for (i in 0 until profilesArr.length()) {
            val o = profilesArr.optJSONObject(i) ?: continue
            db.upsertProfile(
                HealthProfile(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    birthYear = if (o.has("birthYear") && !o.isNull("birthYear")) o.getInt("birthYear") else null,
                    sex = o.optString("sex", ""),
                    phone = o.optString("phone", ""),
                    email = o.optString("email", ""),
                    homeAddress = o.optString("homeAddress", ""),
                    chronicConditions = o.optString("chronicConditions", ""),
                    medications = o.optString("medications", ""),
                    allergies = o.optString("allergies", ""),
                    emergencyContactName = o.optString("emergencyContactName", ""),
                    emergencyContactPhone = o.optString("emergencyContactPhone", ""),
                    autoSmsCheckInEnabled = o.optBoolean("autoSmsCheckInEnabled", false),
                    preferredReportLang = runCatching { ReportLanguage.valueOf(o.optString("preferredReportLang", "RU")) }.getOrDefault(ReportLanguage.RU),
                    createdAtMillis = o.optLong("createdAtMillis", System.currentTimeMillis())
                )
            )
        }

        val weatherArr = root.optJSONArray("weather_snapshots") ?: JSONArray()
        for (i in 0 until weatherArr.length()) {
            val o = weatherArr.optJSONObject(i) ?: continue
            db.insertWeather(
                WeatherSnapshot(
                    id = o.getString("id"),
                    timestampMillis = o.getLong("timestampMillis"),
                    latitude = o.getDouble("latitude"),
                    longitude = o.getDouble("longitude"),
                    locationName = o.getString("locationName"),
                    temperatureC = o.getDouble("temperatureC"),
                    humidityPct = o.getInt("humidityPct"),
                    pressureHpa = o.getDouble("pressureHpa"),
                    pressureMmHg = o.getDouble("pressureMmHg"),
                    pressureDelta6hHpa = o.getDouble("pressureDelta6hHpa"),
                    pressureDelta24hHpa = o.getDouble("pressureDelta24hHpa"),
                    aqiEuropean = if (o.has("aqiEuropean") && !o.isNull("aqiEuropean")) o.getInt("aqiEuropean") else null,
                    pm25 = if (o.has("pm25") && !o.isNull("pm25")) o.getDouble("pm25") else null,
                    kpIndex = if (o.has("kpIndex") && !o.isNull("kpIndex")) o.getDouble("kpIndex") else null,
                    summaryText = o.getString("summaryText")
                )
            )
            restoredRecords++
        }

        val eventsArr = root.optJSONArray("health_events") ?: JSONArray()
        for (i in 0 until eventsArr.length()) {
            val o = eventsArr.optJSONObject(i) ?: continue
            val tagsArr = o.optJSONArray("tags") ?: JSONArray()
            val tags = (0 until tagsArr.length()).mapNotNull { t -> tagsArr.optString(t).takeIf { it.isNotBlank() } }
            db.upsertEvent(
                HealthEvent(
                    id = o.getString("id"),
                    profileId = o.getString("profileId"),
                    timestampMillis = o.getLong("timestampMillis"),
                    category = runCatching { HealthCategory.valueOf(o.getString("category")) }.getOrDefault(HealthCategory.NOTE),
                    title = o.getString("title"),
                    notes = o.optString("notes", ""),
                    severity0to10 = if (o.has("severity0to10") && !o.isNull("severity0to10")) o.getInt("severity0to10") else null,
                    bodyRegion = o.optString("bodyRegion", ""),
                    painCharacter = o.optString("painCharacter", ""),
                    startedAtMillis = if (o.has("startedAtMillis") && !o.isNull("startedAtMillis")) o.getLong("startedAtMillis") else null,
                    tags = tags,
                    sourceTrust = runCatching { DataSourceTrust.valueOf(o.optString("sourceTrust", "USER_INPUT")) }.getOrDefault(DataSourceTrust.USER_INPUT),
                    sourceDetail = o.optString("sourceDetail", ""),
                    weatherSnapshotId = o.optString("weatherSnapshotId", "").takeIf { it.isNotBlank() },
                    attachmentId = o.optString("attachmentId", "").takeIf { it.isNotBlank() },
                    isRedFlagTriggered = o.optBoolean("isRedFlagTriggered", false),
                    clarifyingQaSummary = o.optString("clarifyingQaSummary", "")
                )
            )
            restoredRecords++
        }

        val vitalsArr = root.optJSONArray("vital_measurements") ?: JSONArray()
        for (i in 0 until vitalsArr.length()) {
            val o = vitalsArr.optJSONObject(i) ?: continue
            val attName = o.optString("attachmentFileName", "").takeIf { it.isNotBlank() }
            db.insertVital(
                VitalMeasurement(
                    id = o.getString("id"),
                    profileId = o.getString("profileId"),
                    measuredAtMillis = o.getLong("measuredAtMillis"),
                    type = runCatching { VitalType.valueOf(o.getString("type")) }.getOrDefault(VitalType.HEART_RATE),
                    value1 = o.getDouble("value1"),
                    value2 = if (o.has("value2") && !o.isNull("value2")) o.getDouble("value2") else null,
                    unit = o.optString("unit", ""),
                    contextTag = o.optString("contextTag", ""),
                    sourceTrust = runCatching { DataSourceTrust.valueOf(o.optString("sourceTrust", "USER_INPUT")) }.getOrDefault(DataSourceTrust.USER_INPUT),
                    sourceDetail = o.optString("sourceDetail", ""),
                    attachmentPath = attName?.let { File(attachmentsDir, it).absolutePath }
                )
            )
            restoredRecords++
        }

        val intakesArr = root.optJSONArray("intake_entries") ?: JSONArray()
        for (i in 0 until intakesArr.length()) {
            val o = intakesArr.optJSONObject(i) ?: continue
            val photoName = o.optString("photoFileName", "").takeIf { it.isNotBlank() }
            db.insertIntake(
                IntakeEntry(
                    id = o.getString("id"),
                    profileId = o.getString("profileId"),
                    timestampMillis = o.getLong("timestampMillis"),
                    kind = runCatching { IntakeKind.valueOf(o.getString("kind")) }.getOrDefault(IntakeKind.MEAL),
                    name = o.getString("name"),
                    amountValue = if (o.has("amountValue") && !o.isNull("amountValue")) o.getDouble("amountValue") else null,
                    amountUnit = o.optString("amountUnit", ""),
                    estimatedKcal = if (o.has("estimatedKcal") && !o.isNull("estimatedKcal")) o.getInt("estimatedKcal") else null,
                    proteinG = if (o.has("proteinG") && !o.isNull("proteinG")) o.getDouble("proteinG") else null,
                    fatG = if (o.has("fatG") && !o.isNull("fatG")) o.getDouble("fatG") else null,
                    carbsG = if (o.has("carbsG") && !o.isNull("carbsG")) o.getDouble("carbsG") else null,
                    photoPath = photoName?.let { File(attachmentsDir, it).absolutePath },
                    sourceTrust = runCatching { DataSourceTrust.valueOf(o.optString("sourceTrust", "USER_INPUT")) }.getOrDefault(DataSourceTrust.USER_INPUT),
                    notes = o.optString("notes", "")
                )
            )
            restoredRecords++
        }

        val docsArr = root.optJSONArray("medical_documents") ?: JSONArray()
        for (i in 0 until docsArr.length()) {
            val o = docsArr.optJSONObject(i) ?: continue
            val fName = o.optString("fileName", "")
            db.upsertDocument(
                MedicalDocument(
                    id = o.getString("id"),
                    profileId = o.getString("profileId"),
                    documentDateMillis = o.getLong("documentDateMillis"),
                    createdAtMillis = o.getLong("createdAtMillis"),
                    docType = runCatching { DocType.valueOf(o.getString("docType")) }.getOrDefault(DocType.LAB_RESULT),
                    title = o.getString("title"),
                    doctorOrClinic = o.optString("doctorOrClinic", ""),
                    originalFilePath = File(attachmentsDir, fName).absolutePath,
                    mimeType = o.optString("mimeType", "application/pdf"),
                    ocrText = o.optString("ocrText", ""),
                    extractedSummary = o.optString("extractedSummary", ""),
                    userCorrected = o.optBoolean("userCorrected", false),
                    sourceTrust = runCatching { DataSourceTrust.valueOf(o.optString("sourceTrust", "CLINICIAN_OR_LAB")) }.getOrDefault(DataSourceTrust.CLINICIAN_OR_LAB)
                )
            )
            restoredRecords++
        }

        val labsArr = root.optJSONArray("lab_markers") ?: JSONArray()
        for (i in 0 until labsArr.length()) {
            val o = labsArr.optJSONObject(i) ?: continue
            db.upsertLabMarker(
                LabMarker(
                    id = o.getString("id"),
                    documentId = o.getString("documentId"),
                    profileId = o.getString("profileId"),
                    measuredAtMillis = o.getLong("measuredAtMillis"),
                    markerName = o.getString("markerName"),
                    value = o.getDouble("value"),
                    unit = o.getString("unit"),
                    refMin = if (o.has("refMin") && !o.isNull("refMin")) o.getDouble("refMin") else null,
                    refMax = if (o.has("refMax") && !o.isNull("refMax")) o.getDouble("refMax") else null,
                    flag = runCatching { LabFlag.valueOf(o.optString("flag", "UNKNOWN")) }.getOrDefault(LabFlag.UNKNOWN),
                    userVerified = o.optBoolean("userVerified", false)
                )
            )
            restoredRecords++
        }

        db.logAudit("system", "BACKUP_RESTORE", "Восстановлено $restoredRecords записей и $restoredAttachments вложений")
        return BackupRestoreResult(profilesArr.length(), restoredRecords, restoredAttachments)
    }
}
