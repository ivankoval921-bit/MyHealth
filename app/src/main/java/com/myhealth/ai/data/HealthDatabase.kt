package com.myhealth.ai.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.myhealth.ai.model.AuditLogEntry
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
import java.util.UUID

class HealthDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE profiles (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                birth_year INTEGER,
                sex TEXT NOT NULL DEFAULT '',
                phone TEXT NOT NULL DEFAULT '',
                email TEXT NOT NULL DEFAULT '',
                home_address TEXT NOT NULL DEFAULT '',
                chronic_conditions TEXT NOT NULL DEFAULT '',
                medications TEXT NOT NULL DEFAULT '',
                allergies TEXT NOT NULL DEFAULT '',
                emergency_contact_name TEXT NOT NULL DEFAULT '',
                emergency_contact_phone TEXT NOT NULL DEFAULT '',
                test_emergency_phone TEXT NOT NULL DEFAULT '',
                relatives_json TEXT NOT NULL DEFAULT '[]',
                auto_sms_checkin INTEGER NOT NULL DEFAULT 0,
                preferred_report_lang TEXT NOT NULL DEFAULT 'RU',
                created_at_millis INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE weather_snapshots (
                id TEXT PRIMARY KEY,
                timestamp_millis INTEGER NOT NULL,
                latitude REAL NOT NULL,
                longitude REAL NOT NULL,
                location_name TEXT NOT NULL,
                temperature_c REAL NOT NULL,
                humidity_pct INTEGER NOT NULL,
                pressure_hpa REAL NOT NULL,
                pressure_mmhg REAL NOT NULL,
                pressure_delta_6h_hpa REAL NOT NULL,
                pressure_delta_24h_hpa REAL NOT NULL,
                aqi_european INTEGER,
                pm25 REAL,
                kp_index REAL,
                summary_text TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_weather_ts ON weather_snapshots(timestamp_millis DESC)")

        db.execSQL(
            """
            CREATE TABLE health_events (
                id TEXT PRIMARY KEY,
                profile_id TEXT NOT NULL,
                timestamp_millis INTEGER NOT NULL,
                category TEXT NOT NULL,
                title TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                severity_0_10 INTEGER,
                body_region TEXT NOT NULL DEFAULT '',
                pain_character TEXT NOT NULL DEFAULT '',
                started_at_millis INTEGER,
                tags_json TEXT NOT NULL DEFAULT '[]',
                source_trust TEXT NOT NULL DEFAULT 'USER_INPUT',
                source_detail TEXT NOT NULL DEFAULT '',
                weather_snapshot_id TEXT,
                attachment_id TEXT,
                is_red_flag INTEGER NOT NULL DEFAULT 0,
                clarifying_qa_summary TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(profile_id) REFERENCES profiles(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_events_profile_ts ON health_events(profile_id, timestamp_millis DESC)")

        db.execSQL(
            """
            CREATE TABLE vital_measurements (
                id TEXT PRIMARY KEY,
                profile_id TEXT NOT NULL,
                measured_at_millis INTEGER NOT NULL,
                vital_type TEXT NOT NULL,
                value1 REAL NOT NULL,
                value2 REAL,
                unit TEXT NOT NULL,
                context_tag TEXT NOT NULL DEFAULT '',
                source_trust TEXT NOT NULL DEFAULT 'USER_INPUT',
                source_detail TEXT NOT NULL DEFAULT '',
                attachment_path TEXT,
                FOREIGN KEY(profile_id) REFERENCES profiles(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_vitals_profile_ts ON vital_measurements(profile_id, measured_at_millis DESC)")

        db.execSQL(
            """
            CREATE TABLE intake_entries (
                id TEXT PRIMARY KEY,
                profile_id TEXT NOT NULL,
                timestamp_millis INTEGER NOT NULL,
                kind TEXT NOT NULL,
                name TEXT NOT NULL,
                amount_value REAL,
                amount_unit TEXT NOT NULL DEFAULT '',
                estimated_kcal INTEGER,
                protein_g REAL,
                fat_g REAL,
                carbs_g REAL,
                photo_path TEXT,
                source_trust TEXT NOT NULL DEFAULT 'USER_INPUT',
                notes TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(profile_id) REFERENCES profiles(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_intake_profile_ts ON intake_entries(profile_id, timestamp_millis DESC)")

        db.execSQL(
            """
            CREATE TABLE medical_documents (
                id TEXT PRIMARY KEY,
                profile_id TEXT NOT NULL,
                document_date_millis INTEGER NOT NULL,
                created_at_millis INTEGER NOT NULL,
                doc_type TEXT NOT NULL,
                title TEXT NOT NULL,
                doctor_or_clinic TEXT NOT NULL DEFAULT '',
                original_file_path TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                ocr_text TEXT NOT NULL DEFAULT '',
                extracted_summary TEXT NOT NULL DEFAULT '',
                user_corrected INTEGER NOT NULL DEFAULT 0,
                source_trust TEXT NOT NULL DEFAULT 'CLINICIAN_OR_LAB',
                FOREIGN KEY(profile_id) REFERENCES profiles(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_docs_profile_ts ON medical_documents(profile_id, document_date_millis DESC)")

        db.execSQL(
            """
            CREATE TABLE lab_markers (
                id TEXT PRIMARY KEY,
                document_id TEXT NOT NULL,
                profile_id TEXT NOT NULL,
                measured_at_millis INTEGER NOT NULL,
                marker_name TEXT NOT NULL,
                value REAL NOT NULL,
                unit TEXT NOT NULL,
                ref_min REAL,
                ref_max REAL,
                flag TEXT NOT NULL DEFAULT 'UNKNOWN',
                user_verified INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(document_id) REFERENCES medical_documents(id) ON DELETE CASCADE,
                FOREIGN KEY(profile_id) REFERENCES profiles(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_labs_profile_name ON lab_markers(profile_id, marker_name, measured_at_millis DESC)")

        db.execSQL(
            """
            CREATE TABLE audit_log (
                id TEXT PRIMARY KEY,
                timestamp_millis INTEGER NOT NULL,
                profile_id TEXT NOT NULL,
                action TEXT NOT NULL,
                details TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_audit_ts ON audit_log(timestamp_millis DESC)")

        seedDefaultProfile(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            runCatching { db.execSQL("ALTER TABLE profiles ADD COLUMN home_address TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("ALTER TABLE profiles ADD COLUMN auto_sms_checkin INTEGER NOT NULL DEFAULT 0") }
        }
        if (oldVersion < 3) {
            runCatching { db.execSQL("ALTER TABLE profiles ADD COLUMN phone TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("ALTER TABLE profiles ADD COLUMN email TEXT NOT NULL DEFAULT ''") }
        }
        if (oldVersion < 4) {
            runCatching { db.execSQL("ALTER TABLE profiles ADD COLUMN test_emergency_phone TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("ALTER TABLE profiles ADD COLUMN relatives_json TEXT NOT NULL DEFAULT '[]'") }
        }
    }

    private fun seedDefaultProfile(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        val cv = ContentValues().apply {
            put("id", "profile_primary")
            put("name", "Основной профиль")
            put("preferred_report_lang", ReportLanguage.RU.code)
            put("created_at_millis", now)
        }
        db.insertWithOnConflict("profiles", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized
    fun ensureAtLeastOneProfile(): HealthProfile {
        val all = getProfiles()
        if (all.isNotEmpty()) return all.first()
        val starter = HealthProfile(id = "profile_primary", name = "Основной профиль")
        upsertProfile(starter)
        return starter
    }

    fun getProfiles(): List<HealthProfile> {
        val result = mutableListOf<HealthProfile>()
        readableDatabase.rawQuery(
            "SELECT * FROM profiles ORDER BY created_at_millis ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                result.add(
                    HealthProfile(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        birthYear = c.getColumnIndexOrThrow("birth_year").let { idx -> if (c.isNull(idx)) null else c.getInt(idx) },
                        sex = c.getString(c.getColumnIndexOrThrow("sex")),
                        phone = c.getColumnIndex("phone").let { idx -> if (idx >= 0) c.getString(idx) else "" },
                        email = c.getColumnIndex("email").let { idx -> if (idx >= 0) c.getString(idx) else "" },
                        homeAddress = c.getColumnIndex("home_address").let { idx -> if (idx >= 0) c.getString(idx) else "" },
                        chronicConditions = c.getString(c.getColumnIndexOrThrow("chronic_conditions")),
                        medications = c.getString(c.getColumnIndexOrThrow("medications")),
                        allergies = c.getString(c.getColumnIndexOrThrow("allergies")),
                        emergencyContactName = c.getString(c.getColumnIndexOrThrow("emergency_contact_name")),
                        emergencyContactPhone = c.getString(c.getColumnIndexOrThrow("emergency_contact_phone")),
                        testEmergencyPhone = c.getColumnIndex("test_emergency_phone").let { idx -> if (idx >= 0) c.getString(idx) else "" },
                        relativesJson = c.getColumnIndex("relatives_json").let { idx -> if (idx >= 0) c.getString(idx) else "[]" },
                        autoSmsCheckInEnabled = c.getColumnIndex("auto_sms_checkin").let { idx -> idx >= 0 && c.getInt(idx) == 1 },
                        preferredReportLang = runCatching {
                            ReportLanguage.valueOf(c.getString(c.getColumnIndexOrThrow("preferred_report_lang")))
                        }.getOrDefault(ReportLanguage.RU),
                        createdAtMillis = c.getLong(c.getColumnIndexOrThrow("created_at_millis"))
                    )
                )
            }
        }
        return result
    }

    fun upsertProfile(profile: HealthProfile) {
        val cv = ContentValues().apply {
            put("id", profile.id)
            put("name", profile.name)
            if (profile.birthYear != null) put("birth_year", profile.birthYear) else putNull("birth_year")
            put("sex", profile.sex)
            put("phone", profile.phone)
            put("email", profile.email)
            put("home_address", profile.homeAddress)
            put("chronic_conditions", profile.chronicConditions)
            put("medications", profile.medications)
            put("allergies", profile.allergies)
            put("emergency_contact_name", profile.emergencyContactName)
            put("emergency_contact_phone", profile.emergencyContactPhone)
            put("test_emergency_phone", profile.testEmergencyPhone)
            put("relatives_json", profile.relativesJson)
            put("auto_sms_checkin", if (profile.autoSmsCheckInEnabled) 1 else 0)
            put("preferred_report_lang", profile.preferredReportLang.code)
            put("created_at_millis", profile.createdAtMillis)
        }
        writableDatabase.insertWithOnConflict("profiles", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun insertWeather(snapshot: WeatherSnapshot) {
        val cv = ContentValues().apply {
            put("id", snapshot.id)
            put("timestamp_millis", snapshot.timestampMillis)
            put("latitude", snapshot.latitude)
            put("longitude", snapshot.longitude)
            put("location_name", snapshot.locationName)
            put("temperature_c", snapshot.temperatureC)
            put("humidity_pct", snapshot.humidityPct)
            put("pressure_hpa", snapshot.pressureHpa)
            put("pressure_mmhg", snapshot.pressureMmHg)
            put("pressure_delta_6h_hpa", snapshot.pressureDelta6hHpa)
            put("pressure_delta_24h_hpa", snapshot.pressureDelta24hHpa)
            if (snapshot.aqiEuropean != null) put("aqi_european", snapshot.aqiEuropean) else putNull("aqi_european")
            if (snapshot.pm25 != null) put("pm25", snapshot.pm25) else putNull("pm25")
            if (snapshot.kpIndex != null) put("kp_index", snapshot.kpIndex) else putNull("kp_index")
            put("summary_text", snapshot.summaryText)
        }
        writableDatabase.insertWithOnConflict("weather_snapshots", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getWeatherSnapshots(limit: Int = 500): List<WeatherSnapshot> {
        val result = mutableListOf<WeatherSnapshot>()
        readableDatabase.rawQuery(
            "SELECT * FROM weather_snapshots ORDER BY timestamp_millis DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result.add(
                    WeatherSnapshot(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        timestampMillis = c.getLong(c.getColumnIndexOrThrow("timestamp_millis")),
                        latitude = c.getDouble(c.getColumnIndexOrThrow("latitude")),
                        longitude = c.getDouble(c.getColumnIndexOrThrow("longitude")),
                        locationName = c.getString(c.getColumnIndexOrThrow("location_name")),
                        temperatureC = c.getDouble(c.getColumnIndexOrThrow("temperature_c")),
                        humidityPct = c.getInt(c.getColumnIndexOrThrow("humidity_pct")),
                        pressureHpa = c.getDouble(c.getColumnIndexOrThrow("pressure_hpa")),
                        pressureMmHg = c.getDouble(c.getColumnIndexOrThrow("pressure_mmhg")),
                        pressureDelta6hHpa = c.getDouble(c.getColumnIndexOrThrow("pressure_delta_6h_hpa")),
                        pressureDelta24hHpa = c.getDouble(c.getColumnIndexOrThrow("pressure_delta_24h_hpa")),
                        aqiEuropean = c.getColumnIndexOrThrow("aqi_european").let { idx -> if (c.isNull(idx)) null else c.getInt(idx) },
                        pm25 = c.getColumnIndexOrThrow("pm25").let { idx -> if (c.isNull(idx)) null else c.getDouble(idx) },
                        kpIndex = c.getColumnIndexOrThrow("kp_index").let { idx -> if (c.isNull(idx)) null else c.getDouble(idx) },
                        summaryText = c.getString(c.getColumnIndexOrThrow("summary_text"))
                    )
                )
            }
        }
        return result
    }

    fun upsertEvent(event: HealthEvent) {
        val cv = ContentValues().apply {
            put("id", event.id)
            put("profile_id", event.profileId)
            put("timestamp_millis", event.timestampMillis)
            put("category", event.category.name)
            put("title", event.title)
            put("notes", event.notes)
            if (event.severity0to10 != null) put("severity_0_10", event.severity0to10) else putNull("severity_0_10")
            put("body_region", event.bodyRegion)
            put("pain_character", event.painCharacter)
            if (event.startedAtMillis != null) put("started_at_millis", event.startedAtMillis) else putNull("started_at_millis")
            put("tags_json", JSONArray(event.tags).toString())
            put("source_trust", event.sourceTrust.name)
            put("source_detail", event.sourceDetail)
            if (event.weatherSnapshotId != null) put("weather_snapshot_id", event.weatherSnapshotId) else putNull("weather_snapshot_id")
            if (event.attachmentId != null) put("attachment_id", event.attachmentId) else putNull("attachment_id")
            put("is_red_flag", if (event.isRedFlagTriggered) 1 else 0)
            put("clarifying_qa_summary", event.clarifyingQaSummary)
        }
        writableDatabase.insertWithOnConflict("health_events", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteEvent(id: String) {
        writableDatabase.delete("health_events", "id = ?", arrayOf(id))
    }

    fun getEventsForProfile(profileId: String, limit: Int = 2000): List<HealthEvent> {
        val result = mutableListOf<HealthEvent>()
        readableDatabase.rawQuery(
            "SELECT * FROM health_events WHERE profile_id = ? ORDER BY timestamp_millis DESC LIMIT ?",
            arrayOf(profileId, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                val tagsJson = c.getString(c.getColumnIndexOrThrow("tags_json"))
                val tags = parseStringArray(tagsJson)
                result.add(
                    HealthEvent(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        profileId = c.getString(c.getColumnIndexOrThrow("profile_id")),
                        timestampMillis = c.getLong(c.getColumnIndexOrThrow("timestamp_millis")),
                        category = runCatching {
                            HealthCategory.valueOf(c.getString(c.getColumnIndexOrThrow("category")))
                        }.getOrDefault(HealthCategory.NOTE),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        notes = c.getString(c.getColumnIndexOrThrow("notes")),
                        severity0to10 = c.getColumnIndexOrThrow("severity_0_10").let { idx -> if (c.isNull(idx)) null else c.getInt(idx) },
                        bodyRegion = c.getString(c.getColumnIndexOrThrow("body_region")),
                        painCharacter = c.getString(c.getColumnIndexOrThrow("pain_character")),
                        startedAtMillis = c.getColumnIndexOrThrow("started_at_millis").let { idx -> if (c.isNull(idx)) null else c.getLong(idx) },
                        tags = tags,
                        sourceTrust = runCatching {
                            DataSourceTrust.valueOf(c.getString(c.getColumnIndexOrThrow("source_trust")))
                        }.getOrDefault(DataSourceTrust.USER_INPUT),
                        sourceDetail = c.getString(c.getColumnIndexOrThrow("source_detail")),
                        weatherSnapshotId = c.getString(c.getColumnIndexOrThrow("weather_snapshot_id")),
                        attachmentId = c.getString(c.getColumnIndexOrThrow("attachment_id")),
                        isRedFlagTriggered = c.getInt(c.getColumnIndexOrThrow("is_red_flag")) == 1,
                        clarifyingQaSummary = c.getString(c.getColumnIndexOrThrow("clarifying_qa_summary"))
                    )
                )
            }
        }
        return result
    }

    fun insertVital(vital: VitalMeasurement) {
        val cv = ContentValues().apply {
            put("id", vital.id)
            put("profile_id", vital.profileId)
            put("measured_at_millis", vital.measuredAtMillis)
            put("vital_type", vital.type.name)
            put("value1", vital.value1)
            if (vital.value2 != null) put("value2", vital.value2) else putNull("value2")
            put("unit", vital.unit)
            put("context_tag", vital.contextTag)
            put("source_trust", vital.sourceTrust.name)
            put("source_detail", vital.sourceDetail)
            if (vital.attachmentPath != null) put("attachment_path", vital.attachmentPath) else putNull("attachment_path")
        }
        writableDatabase.insertWithOnConflict("vital_measurements", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteVital(id: String) {
        writableDatabase.delete("vital_measurements", "id = ?", arrayOf(id))
    }

    fun getVitalsForProfile(profileId: String, limit: Int = 2000): List<VitalMeasurement> {
        val result = mutableListOf<VitalMeasurement>()
        readableDatabase.rawQuery(
            "SELECT * FROM vital_measurements WHERE profile_id = ? ORDER BY measured_at_millis DESC LIMIT ?",
            arrayOf(profileId, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result.add(
                    VitalMeasurement(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        profileId = c.getString(c.getColumnIndexOrThrow("profile_id")),
                        measuredAtMillis = c.getLong(c.getColumnIndexOrThrow("measured_at_millis")),
                        type = runCatching {
                            VitalType.valueOf(c.getString(c.getColumnIndexOrThrow("vital_type")))
                        }.getOrDefault(VitalType.HEART_RATE),
                        value1 = c.getDouble(c.getColumnIndexOrThrow("value1")),
                        value2 = c.getColumnIndexOrThrow("value2").let { idx -> if (c.isNull(idx)) null else c.getDouble(idx) },
                        unit = c.getString(c.getColumnIndexOrThrow("unit")),
                        contextTag = c.getString(c.getColumnIndexOrThrow("context_tag")),
                        sourceTrust = runCatching {
                            DataSourceTrust.valueOf(c.getString(c.getColumnIndexOrThrow("source_trust")))
                        }.getOrDefault(DataSourceTrust.USER_INPUT),
                        sourceDetail = c.getString(c.getColumnIndexOrThrow("source_detail")),
                        attachmentPath = c.getString(c.getColumnIndexOrThrow("attachment_path"))
                    )
                )
            }
        }
        return result
    }

    fun insertIntake(entry: IntakeEntry) {
        val cv = ContentValues().apply {
            put("id", entry.id)
            put("profile_id", entry.profileId)
            put("timestamp_millis", entry.timestampMillis)
            put("kind", entry.kind.name)
            put("name", entry.name)
            if (entry.amountValue != null) put("amount_value", entry.amountValue) else putNull("amount_value")
            put("amount_unit", entry.amountUnit)
            if (entry.estimatedKcal != null) put("estimated_kcal", entry.estimatedKcal) else putNull("estimated_kcal")
            if (entry.proteinG != null) put("protein_g", entry.proteinG) else putNull("protein_g")
            if (entry.fatG != null) put("fat_g", entry.fatG) else putNull("fat_g")
            if (entry.carbsG != null) put("carbs_g", entry.carbsG) else putNull("carbs_g")
            if (entry.photoPath != null) put("photo_path", entry.photoPath) else putNull("photo_path")
            put("source_trust", entry.sourceTrust.name)
            put("notes", entry.notes)
        }
        writableDatabase.insertWithOnConflict("intake_entries", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteIntake(id: String) {
        writableDatabase.delete("intake_entries", "id = ?", arrayOf(id))
    }

    fun getIntakesForProfile(profileId: String, limit: Int = 2000): List<IntakeEntry> {
        val result = mutableListOf<IntakeEntry>()
        readableDatabase.rawQuery(
            "SELECT * FROM intake_entries WHERE profile_id = ? ORDER BY timestamp_millis DESC LIMIT ?",
            arrayOf(profileId, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result.add(
                    IntakeEntry(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        profileId = c.getString(c.getColumnIndexOrThrow("profile_id")),
                        timestampMillis = c.getLong(c.getColumnIndexOrThrow("timestamp_millis")),
                        kind = runCatching {
                            IntakeKind.valueOf(c.getString(c.getColumnIndexOrThrow("kind")))
                        }.getOrDefault(IntakeKind.MEAL),
                        name = c.getString(c.getColumnIndexOrThrow("name")),
                        amountValue = c.getColumnIndexOrThrow("amount_value").let { idx -> if (c.isNull(idx)) null else c.getDouble(idx) },
                        amountUnit = c.getString(c.getColumnIndexOrThrow("amount_unit")),
                        estimatedKcal = c.getColumnIndexOrThrow("estimated_kcal").let { idx -> if (c.isNull(idx)) null else c.getInt(idx) },
                        proteinG = c.getColumnIndexOrThrow("protein_g").let { idx -> if (c.isNull(idx)) null else c.getDouble(idx) },
                        fatG = c.getColumnIndexOrThrow("fat_g").let { idx -> if (c.isNull(idx)) null else c.getDouble(idx) },
                        carbsG = c.getColumnIndexOrThrow("carbs_g").let { idx -> if (c.isNull(idx)) null else c.getDouble(idx) },
                        photoPath = c.getString(c.getColumnIndexOrThrow("photo_path")),
                        sourceTrust = runCatching {
                            DataSourceTrust.valueOf(c.getString(c.getColumnIndexOrThrow("source_trust")))
                        }.getOrDefault(DataSourceTrust.USER_INPUT),
                        notes = c.getString(c.getColumnIndexOrThrow("notes"))
                    )
                )
            }
        }
        return result
    }

    fun upsertDocument(doc: MedicalDocument) {
        val cv = ContentValues().apply {
            put("id", doc.id)
            put("profile_id", doc.profileId)
            put("document_date_millis", doc.documentDateMillis)
            put("created_at_millis", doc.createdAtMillis)
            put("doc_type", doc.docType.name)
            put("title", doc.title)
            put("doctor_or_clinic", doc.doctorOrClinic)
            put("original_file_path", doc.originalFilePath)
            put("mime_type", doc.mimeType)
            put("ocr_text", doc.ocrText)
            put("extracted_summary", doc.extractedSummary)
            put("user_corrected", if (doc.userCorrected) 1 else 0)
            put("source_trust", doc.sourceTrust.name)
        }
        writableDatabase.insertWithOnConflict("medical_documents", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteDocument(id: String) {
        writableDatabase.delete("medical_documents", "id = ?", arrayOf(id))
    }

    fun getDocumentsForProfile(profileId: String, limit: Int = 1000): List<MedicalDocument> {
        val result = mutableListOf<MedicalDocument>()
        readableDatabase.rawQuery(
            "SELECT * FROM medical_documents WHERE profile_id = ? ORDER BY document_date_millis DESC LIMIT ?",
            arrayOf(profileId, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result.add(
                    MedicalDocument(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        profileId = c.getString(c.getColumnIndexOrThrow("profile_id")),
                        documentDateMillis = c.getLong(c.getColumnIndexOrThrow("document_date_millis")),
                        createdAtMillis = c.getLong(c.getColumnIndexOrThrow("created_at_millis")),
                        docType = runCatching {
                            DocType.valueOf(c.getString(c.getColumnIndexOrThrow("doc_type")))
                        }.getOrDefault(DocType.LAB_RESULT),
                        title = c.getString(c.getColumnIndexOrThrow("title")),
                        doctorOrClinic = c.getString(c.getColumnIndexOrThrow("doctor_or_clinic")),
                        originalFilePath = c.getString(c.getColumnIndexOrThrow("original_file_path")),
                        mimeType = c.getString(c.getColumnIndexOrThrow("mime_type")),
                        ocrText = c.getString(c.getColumnIndexOrThrow("ocr_text")),
                        extractedSummary = c.getString(c.getColumnIndexOrThrow("extracted_summary")),
                        userCorrected = c.getInt(c.getColumnIndexOrThrow("user_corrected")) == 1,
                        sourceTrust = runCatching {
                            DataSourceTrust.valueOf(c.getString(c.getColumnIndexOrThrow("source_trust")))
                        }.getOrDefault(DataSourceTrust.CLINICIAN_OR_LAB)
                    )
                )
            }
        }
        return result
    }

    fun upsertLabMarker(marker: LabMarker) {
        val cv = ContentValues().apply {
            put("id", marker.id)
            put("document_id", marker.documentId)
            put("profile_id", marker.profileId)
            put("measured_at_millis", marker.measuredAtMillis)
            put("marker_name", marker.markerName)
            put("value", marker.value)
            put("unit", marker.unit)
            if (marker.refMin != null) put("ref_min", marker.refMin) else putNull("ref_min")
            if (marker.refMax != null) put("ref_max", marker.refMax) else putNull("ref_max")
            put("flag", marker.flag.name)
            put("user_verified", if (marker.userVerified) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("lab_markers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun insertLabMarkers(markers: List<LabMarker>) {
        if (markers.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            markers.forEach { upsertLabMarker(it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getLabMarkersForProfile(profileId: String, limit: Int = 3000): List<LabMarker> {
        val result = mutableListOf<LabMarker>()
        readableDatabase.rawQuery(
            "SELECT * FROM lab_markers WHERE profile_id = ? ORDER BY measured_at_millis DESC LIMIT ?",
            arrayOf(profileId, limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result.add(
                    LabMarker(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        documentId = c.getString(c.getColumnIndexOrThrow("document_id")),
                        profileId = c.getString(c.getColumnIndexOrThrow("profile_id")),
                        measuredAtMillis = c.getLong(c.getColumnIndexOrThrow("measured_at_millis")),
                        markerName = c.getString(c.getColumnIndexOrThrow("marker_name")),
                        value = c.getDouble(c.getColumnIndexOrThrow("value")),
                        unit = c.getString(c.getColumnIndexOrThrow("unit")),
                        refMin = c.getColumnIndexOrThrow("ref_min").let { idx -> if (c.isNull(idx)) null else c.getDouble(idx) },
                        refMax = c.getColumnIndexOrThrow("ref_max").let { idx -> if (c.isNull(idx)) null else c.getDouble(idx) },
                        flag = runCatching {
                            LabFlag.valueOf(c.getString(c.getColumnIndexOrThrow("flag")))
                        }.getOrDefault(LabFlag.UNKNOWN),
                        userVerified = c.getInt(c.getColumnIndexOrThrow("user_verified")) == 1
                    )
                )
            }
        }
        return result
    }

    fun logAudit(profileId: String, action: String, details: String) {
        val cv = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            put("timestamp_millis", System.currentTimeMillis())
            put("profile_id", profileId)
            put("action", action)
            put("details", details)
        }
        writableDatabase.insert("audit_log", null, cv)
    }

    fun getAuditLogs(limit: Int = 200): List<AuditLogEntry> {
        val result = mutableListOf<AuditLogEntry>()
        readableDatabase.rawQuery(
            "SELECT * FROM audit_log ORDER BY timestamp_millis DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                result.add(
                    AuditLogEntry(
                        id = c.getString(c.getColumnIndexOrThrow("id")),
                        timestampMillis = c.getLong(c.getColumnIndexOrThrow("timestamp_millis")),
                        profileId = c.getString(c.getColumnIndexOrThrow("profile_id")),
                        action = c.getString(c.getColumnIndexOrThrow("action")),
                        details = c.getString(c.getColumnIndexOrThrow("details"))
                    )
                )
            }
        }
        return result
    }

    private fun parseStringArray(raw: String): List<String> {
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i -> arr.optString(i).trim().takeIf { it.isNotEmpty() } }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val DB_NAME = "myhealth_vault.db"
        private const val DB_VERSION = 4

        @Volatile
        private var instance: HealthDatabase? = null

        fun getInstance(context: Context): HealthDatabase =
            instance ?: synchronized(this) {
                instance ?: HealthDatabase(context.applicationContext).also { instance = it }
            }
    }
}
