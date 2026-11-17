package com.myhealth.ai.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.myhealth.ai.data.BackupManager
import com.myhealth.ai.data.HealthDatabase
import com.myhealth.ai.data.HealthPrefs
import com.myhealth.ai.logic.AppMeta
import com.myhealth.ai.logic.AppUpdateInfo
import com.myhealth.ai.logic.CorrelationEngine
import com.myhealth.ai.logic.DoctorReportPdfGenerator
import com.myhealth.ai.logic.GeminiHealthClient
import com.myhealth.ai.logic.MedicalDocumentEngine
import com.myhealth.ai.logic.RedFlagSafetyEngine
import com.myhealth.ai.logic.SymptomAndClarifierEngine
import com.myhealth.ai.logic.UpdateChecker
import com.myhealth.ai.logic.WeatherClient
import com.myhealth.ai.model.ActionSuggestion
import com.myhealth.ai.model.AiSettings
import com.myhealth.ai.model.AuditLogEntry
import com.myhealth.ai.model.ClarifyingQuestion
import com.myhealth.ai.model.CorrelationInsight
import com.myhealth.ai.model.DataSourceTrust
import com.myhealth.ai.model.DocType
import com.myhealth.ai.model.HealthCategory
import com.myhealth.ai.model.HealthEvent
import com.myhealth.ai.model.HealthProfile
import com.myhealth.ai.model.IntakeEntry
import com.myhealth.ai.model.IntakeKind
import com.myhealth.ai.model.LabMarker
import com.myhealth.ai.model.LabTrendSummary
import com.myhealth.ai.model.MedicalDocument
import com.myhealth.ai.model.RedFlagAssessment
import com.myhealth.ai.model.ReportLanguage
import com.myhealth.ai.model.VitalMeasurement
import com.myhealth.ai.model.VitalType
import com.myhealth.ai.model.WeatherSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

enum class MainTab(val titleRu: String) {
    TODAY("Сегодня"),
    HISTORY("История"),
    ANALYTICS("Тренды"),
    DOCS("Карта"),
    PROFILE("Профиль")
}

sealed interface UiEffect {
    data class SetCheckInAlarm(val active: Boolean) : UiEffect
    data class SendAutoEmergencySms(val phone: String, val message: String) : UiEffect
    data class InstallUpdateApk(val file: File) : UiEffect
}

data class MyHealthUiState(
    val activeTab: MainTab = MainTab.TODAY,
    val profiles: List<HealthProfile> = emptyList(),
    val activeProfile: HealthProfile? = null,
    val events: List<HealthEvent> = emptyList(),
    val vitals: List<VitalMeasurement> = emptyList(),
    val intakes: List<IntakeEntry> = emptyList(),
    val documents: List<MedicalDocument> = emptyList(),
    val labMarkers: List<LabMarker> = emptyList(),
    val labTrends: List<LabTrendSummary> = emptyList(),
    val correlations: List<CorrelationInsight> = emptyList(),
    val latestWeather: WeatherSnapshot? = null,
    val weatherHistory: List<WeatherSnapshot> = emptyList(),
    val auditLogs: List<AuditLogEntry> = emptyList(),
    val inputText: String = "",
    val partialSpeechText: String = "",
    val isListening: Boolean = false,
    val selectedSeverity: Int? = null,
    val selectedTags: Set<String> = emptySet(),
    val activeClarifyingEvent: HealthEvent? = null,
    val activeQuestions: List<ClarifyingQuestion> = emptyList(),
    val actionSuggestions: List<ActionSuggestion> = emptyList(),
    val activeRedFlag: RedFlagAssessment? = null,
    val aiGuidanceNote: String = "",
    val aiDoctorBriefText: String = "",
    val historySearchQuery: String = "",
    val historyCategoryFilter: HealthCategory? = null,
    val isBusy: Boolean = false,
    val statusBanner: String = "",
    val micGranted: Boolean = false,
    val cameraGranted: Boolean = false,
    val locationGranted: Boolean = false,
    val smsGranted: Boolean = false,
    val lastGpsText: String = "координаты не определены",
    val checkInRemainingSeconds: Int = 0,
    val checkInGraceSeconds: Int = 0,
    val showEmergencyCardDialog: Boolean = false,
    val emergencyCardTextRu: String = "",
    val emergencyCardTextDe: String = "",
    val installedVersionName: String = AppMeta.VERSION_NAME_FALLBACK,
    val installedVersionCode: Long = AppMeta.VERSION_CODE_FALLBACK,
    val availableUpdate: AppUpdateInfo? = null,
    val updateChecking: Boolean = false,
    val updateDownloadProgress: Int? = null,
    val updateStatusNote: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val db = HealthDatabase.getInstance(application)
    val prefs = HealthPrefs(application)
    val aiSettings: StateFlow<AiSettings> = prefs.aiSettings

    private val _ui = MutableStateFlow(
        MyHealthUiState(
            installedVersionName = AppMeta.installedVersionName(application),
            installedVersionCode = AppMeta.installedVersionCode(application)
        )
    )
    val ui: StateFlow<MyHealthUiState> = _ui.asStateFlow()

    private val _effects = MutableSharedFlow<UiEffect>(extraBufferCapacity = 12)
    val effects: SharedFlow<UiEffect> = _effects.asSharedFlow()

    private var checkInJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            db.ensureAtLeastOneProfile()
            reloadAll()
            UpdateChecker.schedulePeriodicChecks(getApplication())
            checkForAppUpdateInternal(silent = true)
        }
    }

    fun selectTab(tab: MainTab) {
        _ui.update { it.copy(activeTab = tab) }
    }

    fun updatePermissions(mic: Boolean, camera: Boolean, location: Boolean, sms: Boolean = false) {
        _ui.update { it.copy(micGranted = mic, cameraGranted = camera, locationGranted = location, smsGranted = sms) }
    }

    fun dismissActionSuggestion(key: String) {
        _ui.update { st -> st.copy(actionSuggestions = st.actionSuggestions.filterNot { it.key == key }) }
    }

    fun setInputText(text: String) {
        _ui.update { it.copy(inputText = text) }
    }

    fun setPartialSpeech(text: String) {
        _ui.update { it.copy(partialSpeechText = text) }
    }

    fun appendDictatedText(spoken: String) {
        val clean = spoken.trim()
        if (clean.isEmpty()) return
        _ui.update { st ->
            val next = if (st.inputText.isBlank()) clean else "${st.inputText.trimEnd()} $clean"
            st.copy(inputText = next, partialSpeechText = "")
        }
    }

    fun setListening(listening: Boolean) {
        _ui.update { it.copy(isListening = listening) }
    }

    fun toggleSeverity(sev: Int) {
        _ui.update { st ->
            st.copy(selectedSeverity = if (st.selectedSeverity == sev) null else sev)
        }
    }

    fun toggleTag(tag: String) {
        _ui.update { st ->
            val next = if (tag in st.selectedTags) st.selectedTags - tag else st.selectedTags + tag
            st.copy(selectedTags = next)
        }
    }

    fun dismissRedFlag() {
        _ui.update { it.copy(activeRedFlag = null) }
    }

    fun dismissClarifyingCard() {
        _ui.update { it.copy(activeClarifyingEvent = null, activeQuestions = emptyList()) }
    }

    fun clearBanner() {
        _ui.update { it.copy(statusBanner = "") }
    }

    fun showBanner(msg: String) {
        _ui.update { it.copy(statusBanner = msg) }
    }

    fun setHistoryFilter(query: String, category: HealthCategory?) {
        _ui.update { it.copy(historySearchQuery = query, historyCategoryFilter = category) }
    }

    fun refreshWeather(lat: Double = prefs.lastLat, lon: Double = prefs.lastLon, locName: String = prefs.lastLocationName) {
        prefs.lastLat = lat
        prefs.lastLon = lon
        prefs.lastLocationName = locName
        val gpsText = String.format(Locale.US, "%.5f, %.5f (%s)", lat, lon, locName)
        _ui.update { it.copy(lastGpsText = gpsText) }
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(isBusy = true) }
            runCatching {
                val snap = WeatherClient.fetchCurrentWeather(
                    latitude = lat,
                    longitude = lon,
                    locationName = locName,
                    includeGeomagneticKp = aiSettings.value.includeGeomagneticRef
                )
                db.insertWeather(snap)
                reloadAll()
                _ui.update { it.copy(isBusy = false, statusBanner = "Погода и давление обновлены") }
            }.getOrElse { ex ->
                _ui.update { it.copy(isBusy = false, statusBanner = "Погода недоступна: ${ex.message}") }
            }
        }
    }

    fun submitQuickHealthEntry() {
        val st = _ui.value
        val profile = st.activeProfile ?: return
        val raw = st.inputText.trim()
        if (raw.isEmpty() && st.selectedTags.isEmpty() && st.selectedSeverity == null) {
            showBanner("Надиктуйте состояние, введите текст или выберите быстрые теги")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(isBusy = true, aiGuidanceNote = "") }
            val weatherId = st.latestWeather?.id
            val settings = aiSettings.value
            val now = System.currentTimeMillis()

            // Always evaluate offline parser + Red Flags immediately first (with chronic history context)
            val offline = SymptomAndClarifierEngine.parseOffline(
                rawText = raw,
                profileId = profile.id,
                selectedTags = st.selectedTags,
                explicitSeverity = st.selectedSeverity,
                weatherSnapshotId = weatherId,
                priorEvents = st.events,
                recentVitals = st.vitals,
                nowMillis = now
            )

            val allVitalsForCheck = offline.vitals + st.vitals
            val immediateRedFlag = RedFlagSafetyEngine.evaluate(
                text = raw + " " + st.selectedTags.joinToString(" "),
                severity0to10 = st.selectedSeverity ?: offline.events.firstOrNull()?.severity0to10,
                latestVitals = allVitalsForCheck,
                priorEvents = st.events,
                nowMillis = now
            )

            // Optionally enhance parsing, proactive checks, and systemic follow-ups with Gemini
            val geminiParsed = if (settings.isGeminiReady && raw.isNotBlank()) {
                db.logAudit(profile.id, "AI_SYMPTOM_PARSE", "Запрос в ${settings.geminiModel} (store=false)")
                GeminiHealthClient.analyzeSymptomAndClarify(
                    rawText = raw,
                    profile = profile,
                    weather = st.latestWeather,
                    priorEvents = st.events,
                    recentVitals = allVitalsForCheck,
                    settings = settings,
                    nowMillis = now
                )
            } else null

            val finalEvents = if (geminiParsed != null && geminiParsed.events.isNotEmpty()) {
                geminiParsed.events.map { ev ->
                    ev.copy(
                        severity0to10 = ev.severity0to10 ?: st.selectedSeverity,
                        tags = (ev.tags + st.selectedTags).distinct(),
                        isRedFlagTriggered = immediateRedFlag.isUrgent
                    )
                }
            } else {
                offline.events
            }

            finalEvents.forEach { db.upsertEvent(it) }
            offline.vitals.forEach { db.insertVital(it) }
            offline.intakes.forEach { db.insertIntake(it) }
            db.logAudit(profile.id, "LOG_ENTRY", "Записано: событий=${finalEvents.size}, замеров=${offline.vitals.size}, привычек=${offline.intakes.size}")

            val questions = when {
                immediateRedFlag.isUrgent -> emptyList()
                geminiParsed != null && geminiParsed.followUpQuestions.isNotEmpty() -> geminiParsed.followUpQuestions
                else -> offline.missingQuestions
            }
            val mergedActions = (offline.actionSuggestions + geminiParsed?.actionSuggestions.orEmpty())
                .distinctBy { it.key }
                .take(4)

            reloadAll()
            _ui.update {
                it.copy(
                    isBusy = false,
                    inputText = "",
                    selectedSeverity = null,
                    selectedTags = emptySet(),
                    activeClarifyingEvent = finalEvents.firstOrNull(),
                    activeQuestions = questions,
                    actionSuggestions = mergedActions,
                    activeRedFlag = if (immediateRedFlag.isUrgent || immediateRedFlag.isChronicContextNote || immediateRedFlag.tier.ordinal >= 2) immediateRedFlag else null,
                    aiGuidanceNote = geminiParsed?.guidanceText.orEmpty(),
                    statusBanner = when {
                        immediateRedFlag.isUrgent -> "ВНИМАНИЕ: выявлены тревожные признаки"
                        immediateRedFlag.isChronicContextNote -> "Учтено как повторяющийся хронический признак"
                        else -> "Сохранено в хронику здоровья"
                    }
                )
            }
        }
    }

    fun answerClarifyingQuestion(question: ClarifyingQuestion, answer: String) {
        val event = _ui.value.activeClarifyingEvent ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val qaAppend = "${question.promptRu} → $answer"
            val updatedQa = if (event.clarifyingQaSummary.isBlank()) qaAppend else "${event.clarifyingQaSummary}; $qaAppend"
            val sevFromOpt = Regex("^(\\d{1,2})\\b").find(answer)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0..10 }
            val updatedEvent = event.copy(
                clarifyingQaSummary = updatedQa,
                bodyRegion = if (question.key == "region") answer else event.bodyRegion,
                painCharacter = if (question.key == "character") answer else event.painCharacter,
                severity0to10 = if (question.key == "severity" && sevFromOpt != null) sevFromOpt else event.severity0to10,
                tags = if (question.key == "triggers") (event.tags + answer).distinct() else event.tags
            )
            db.upsertEvent(updatedEvent)
            val reEval = RedFlagSafetyEngine.evaluate(
                text = updatedEvent.notes + " " + updatedQa,
                severity0to10 = updatedEvent.severity0to10,
                latestVitals = _ui.value.vitals,
                priorEvents = _ui.value.events
            )
            reloadAll()
            _ui.update { st ->
                st.copy(
                    activeClarifyingEvent = updatedEvent,
                    activeQuestions = st.activeQuestions.filterNot { it.key == question.key },
                    activeRedFlag = if (reEval.isUrgent) reEval else st.activeRedFlag,
                    statusBanner = "Уточнение добавлено"
                )
            }
        }
    }

    fun logQuickVital(
        type: VitalType,
        value1: Double,
        value2: Double? = null,
        contextTag: String = "",
        trust: DataSourceTrust = DataSourceTrust.VALIDATED_DEVICE,
        sourceDetail: String = "Ручной ввод с прибора"
    ) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val vital = VitalMeasurement(
                id = UUID.randomUUID().toString(),
                profileId = profile.id,
                measuredAtMillis = System.currentTimeMillis(),
                type = type,
                value1 = value1,
                value2 = value2,
                unit = type.defaultUnit,
                contextTag = contextTag,
                sourceTrust = trust,
                sourceDetail = sourceDetail
            )
            db.insertVital(vital)
            val label = if (value2 != null) "${type.titleRu}: ${value1.toInt()}/${value2.toInt()} ${type.defaultUnit}"
            else "${type.titleRu}: $value1 ${type.defaultUnit}"
            db.upsertEvent(
                HealthEvent(
                    id = UUID.randomUUID().toString(),
                    profileId = profile.id,
                    timestampMillis = vital.measuredAtMillis,
                    category = if (type == VitalType.SLEEP_HOURS || type == VitalType.STEPS) HealthCategory.SLEEP_ACTIVITY else HealthCategory.VITALS,
                    title = label,
                    notes = contextTag,
                    sourceTrust = trust,
                    sourceDetail = sourceDetail,
                    weatherSnapshotId = _ui.value.latestWeather?.id
                )
            )
            val rf = RedFlagSafetyEngine.evaluate(
                text = label,
                severity0to10 = null,
                latestVitals = listOf(vital),
                priorEvents = _ui.value.events
            )
            reloadAll()
            _ui.update { st ->
                val clearedKeys = when (type) {
                    VitalType.BLOOD_PRESSURE, VitalType.HEART_RATE -> setOf("measure_bp", "photo_tonometer")
                    VitalType.TEMPERATURE_C -> setOf("measure_temp")
                    else -> emptySet()
                }
                st.copy(
                    actionSuggestions = st.actionSuggestions.filterNot { it.key in clearedKeys },
                    activeRedFlag = if (rf.isUrgent) rf else st.activeRedFlag,
                    statusBanner = "Замер сохранён: $label"
                )
            }
        }
    }

    fun logQuickIntake(kind: IntakeKind, name: String, amount: Double?, unit: String, notes: String = "") {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val entry = IntakeEntry(
                id = UUID.randomUUID().toString(),
                profileId = profile.id,
                timestampMillis = now,
                kind = kind,
                name = name,
                amountValue = amount,
                amountUnit = unit,
                notes = notes
            )
            db.insertIntake(entry)
            val amountStr = amount?.let { " (${if (it % 1.0 == 0.0) it.toInt() else it} $unit)" } ?: ""
            db.upsertEvent(
                HealthEvent(
                    id = UUID.randomUUID().toString(),
                    profileId = profile.id,
                    timestampMillis = now,
                    category = if (kind == IntakeKind.MEDICATION || kind == IntakeKind.SMOKING) HealthCategory.HABIT_MEDS else HealthCategory.FOOD_DRINK,
                    title = "$name$amountStr",
                    notes = notes,
                    tags = listOf(kind.titleRu),
                    weatherSnapshotId = _ui.value.latestWeather?.id
                )
            )
            reloadAll()
            _ui.update { it.copy(statusBanner = "Добавлено: $name$amountStr") }
        }
    }

    fun importMealPhoto(sourceUri: Uri) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(isBusy = true) }
            runCatching {
                val (storedFile, _, origName) = MedicalDocumentEngine.copyUriToAttachments(
                    getApplication(),
                    sourceUri,
                    forcedMime = "image/jpeg"
                )
                val bmp = BitmapFactory.decodeFile(storedFile.absolutePath)
                val jpegBytes = if (bmp != null) {
                    val scaled = scaleBitmap(bmp, 1200)
                    ByteArrayOutputStream().use { bos ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, 82, bos)
                        bos.toByteArray()
                    }
                } else storedFile.readBytes()

                val settings = aiSettings.value
                if (settings.isGeminiReady) {
                    db.logAudit(profile.id, "AI_MEAL_PHOTO", "Фото блюда в ${settings.geminiModel} (store=false)")
                    val res = GeminiHealthClient.analyzeMealPhoto(jpegBytes, storedFile.absolutePath, profile.id, settings)
                    if (res.ok && res.entry != null) {
                        db.insertIntake(res.entry)
                        db.upsertEvent(
                            HealthEvent(
                                id = UUID.randomUUID().toString(),
                                profileId = profile.id,
                                timestampMillis = res.entry.timestampMillis,
                                category = HealthCategory.FOOD_DRINK,
                                title = res.summaryText,
                                notes = res.entry.notes,
                                tags = listOf("Питание", "Фото блюда"),
                                sourceTrust = DataSourceTrust.AI_HYPOTHESIS,
                                sourceDetail = settings.geminiModel,
                                weatherSnapshotId = _ui.value.latestWeather?.id
                            )
                        )
                        reloadAll()
                        _ui.update { it.copy(isBusy = false, statusBanner = res.summaryText) }
                        return@launch
                    }
                }

                // Fallback without Gemini / consent: store meal photo record cleanly
                val fallback = IntakeEntry(
                    id = UUID.randomUUID().toString(),
                    profileId = profile.id,
                    timestampMillis = System.currentTimeMillis(),
                    kind = IntakeKind.MEAL,
                    name = "Приём пищи ($origName)",
                    photoPath = storedFile.absolutePath,
                    notes = "Фото сохранено локально (включите Gemini в Профиле для оценки БЖУ)"
                )
                db.insertIntake(fallback)
                db.upsertEvent(
                    HealthEvent(
                        id = UUID.randomUUID().toString(),
                        profileId = profile.id,
                        timestampMillis = fallback.timestampMillis,
                        category = HealthCategory.FOOD_DRINK,
                        title = fallback.name,
                        notes = fallback.notes,
                        tags = listOf("Фото блюда")
                    )
                )
                reloadAll()
                _ui.update { it.copy(isBusy = false, statusBanner = "Фото еды сохранено локально") }
            }.getOrElse { ex ->
                _ui.update { it.copy(isBusy = false, statusBanner = "Ошибка фото блюда: ${ex.message}") }
            }
        }
    }

    fun importMedicalFile(sourceUri: Uri, docType: DocType, forcedMime: String? = null) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(isBusy = true) }
            runCatching {
                val (storedFile, mime, origName) = MedicalDocumentEngine.copyUriToAttachments(
                    getApplication(),
                    sourceUri,
                    forcedMime = forcedMime
                )
                val settings = aiSettings.value
                if (settings.isGeminiReady) {
                    db.logAudit(profile.id, "AI_DOC_SCAN", "Анализ документа ${docType.name} в ${settings.geminiModel} (store=false)")
                }
                val result = MedicalDocumentEngine.processStoredDocument(
                    storedFile = storedFile,
                    mime = mime,
                    displayName = origName,
                    docType = docType,
                    profileId = profile.id,
                    settings = settings
                )
                db.upsertDocument(result.document)
                db.insertLabMarkers(result.extractedMarkers)
                db.upsertEvent(
                    HealthEvent(
                        id = UUID.randomUUID().toString(),
                        profileId = profile.id,
                        timestampMillis = result.document.documentDateMillis,
                        category = HealthCategory.DOCUMENT_LAB,
                        title = result.document.title,
                        notes = result.document.extractedSummary,
                        tags = listOf(docType.titleRu),
                        sourceTrust = result.document.sourceTrust,
                        sourceDetail = result.document.doctorOrClinic.ifBlank { origName },
                        attachmentId = result.document.id
                    )
                )
                db.logAudit(profile.id, "DOC_IMPORT", "${result.document.title} (${result.extractedMarkers.size} показателей)")
                reloadAll()
                _ui.update { it.copy(isBusy = false, statusBanner = result.statusMessage) }
            }.getOrElse { ex ->
                _ui.update { it.copy(isBusy = false, statusBanner = "Ошибка импорта документа: ${ex.message}") }
            }
        }
    }

    fun updateDocumentManualCorrection(doc: MedicalDocument, newTitle: String, newClinic: String, newSummary: String) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val updated = doc.copy(
                title = newTitle.trim().ifBlank { doc.title },
                doctorOrClinic = newClinic.trim(),
                extractedSummary = newSummary.trim(),
                userCorrected = true
            )
            db.upsertDocument(updated)
            db.logAudit(profile.id, "DOC_CORRECTION", "Исправлено пользователем: ${updated.title}")
            reloadAll()
            _ui.update { it.copy(statusBanner = "Документ обновлён и помечен как проверенный") }
        }
    }

    fun deleteEvent(id: String) {
        val profileId = _ui.value.activeProfile?.id ?: return
        viewModelScope.launch(Dispatchers.IO) {
            db.deleteEvent(id)
            db.logAudit(profileId, "DELETE_EVENT", "Удалена запись $id")
            reloadAll()
        }
    }

    fun deleteDocument(doc: MedicalDocument) {
        viewModelScope.launch(Dispatchers.IO) {
            db.deleteDocument(doc.id)
            runCatching { File(doc.originalFilePath).delete() }
            db.logAudit(doc.profileId, "DOC_DELETE", "Удалён документ ${doc.title}")
            reloadAll()
            _ui.update { it.copy(statusBanner = "Документ удалён") }
        }
    }

    fun generateAiDoctorReflection() {
        val st = _ui.value
        val profile = st.activeProfile ?: return
        val settings = aiSettings.value
        if (!settings.isGeminiReady) {
            showBanner("Включите согласие ИИ и укажите ключ Gemini в Профиле")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(isBusy = true) }
            db.logAudit(profile.id, "AI_DOCTOR_PREP", "Подготовка выписки в ${settings.geminiModel} (store=false)")
            val text = GeminiHealthClient.buildDoctorVisitReflection(
                profile = profile,
                events = st.events,
                vitals = st.vitals,
                correlations = st.correlations,
                docs = st.documents,
                lang = profile.preferredReportLang,
                settings = settings
            )
            _ui.update {
                it.copy(
                    isBusy = false,
                    aiDoctorBriefText = text ?: "Не удалось получить ответ от Gemini — проверьте интернет и ключ API",
                    statusBanner = if (text != null) "ИИ-подготовка к визиту готова" else "Ошибка запроса Gemini"
                )
            }
        }
    }

    fun buildDoctorPdf(daysBack: Int, lang: ReportLanguage, onPdfReady: (File) -> Unit) {
        val st = _ui.value
        val profile = st.activeProfile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(isBusy = true) }
            runCatching {
                val pdfFile = DoctorReportPdfGenerator.generateReportPdf(
                    context = getApplication(),
                    profile = profile,
                    lang = lang,
                    daysBack = daysBack,
                    events = st.events,
                    vitals = st.vitals,
                    intakes = st.intakes,
                    docs = st.documents,
                    labs = st.labMarkers,
                    correlations = st.correlations,
                    aiBriefText = st.aiDoctorBriefText.takeIf { it.isNotBlank() }
                )
                db.logAudit(profile.id, "PDF_REPORT", "Создан отчёт ${pdfFile.name} (${lang.code}, $daysBack дн.)")
                reloadAll()
                _ui.update { it.copy(isBusy = false, statusBanner = "PDF-отчёт создан: ${pdfFile.name}") }
                launch(Dispatchers.Main) { onPdfReady(pdfFile) }
            }.getOrElse { ex ->
                _ui.update { it.copy(isBusy = false, statusBanner = "Ошибка PDF: ${ex.message}") }
            }
        }
    }

    fun switchProfile(profileId: String) {
        prefs.activeProfileId = profileId
        viewModelScope.launch(Dispatchers.IO) {
            db.logAudit(profileId, "PROFILE_SWITCH", "Переключение активного профиля")
            reloadAll()
        }
    }

    fun saveProfile(profile: HealthProfile) {
        prefs.activeProfileId = profile.id
        viewModelScope.launch(Dispatchers.IO) {
            db.upsertProfile(profile)
            db.logAudit(profile.id, "PROFILE_SAVE", "Сохранён профиль ${profile.name}")
            reloadAll()
            _ui.update { it.copy(statusBanner = "Профиль «${profile.name}» сохранён") }
        }
    }

    fun updateAiSettings(settings: AiSettings) {
        prefs.updateAiSettings(settings)
        viewModelScope.launch(Dispatchers.IO) {
            val pid = _ui.value.activeProfile?.id ?: "system"
            db.logAudit(pid, "AI_SETTINGS", "consent=${settings.aiConsentGranted}, model=${settings.geminiModel}")
            reloadAll()
        }
    }

    fun testGeminiConnection() {
        val settings = aiSettings.value
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(isBusy = true) }
            val res = GeminiHealthClient.testConnection(settings)
            _ui.update {
                it.copy(
                    isBusy = false,
                    statusBanner = res.getOrElse { ex -> "Ошибка Gemini: ${ex.message}" }
                )
            }
        }
    }

    fun exportBackupZip(onZipReady: (File) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(isBusy = true) }
            runCatching {
                val res = BackupManager.exportAllToZip(getApplication(), db)
                reloadAll()
                _ui.update {
                    it.copy(
                        isBusy = false,
                        statusBanner = "Бэкап создан: ${res.totalRecords} записей, ${res.attachmentCount} файлов"
                    )
                }
                launch(Dispatchers.Main) { onZipReady(res.zipFile) }
            }.getOrElse { ex ->
                _ui.update { it.copy(isBusy = false, statusBanner = "Ошибка бэкапа: ${ex.message}") }
            }
        }
    }

    fun restoreBackupZip(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(isBusy = true) }
            runCatching {
                val stream = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?: error("Не удалось открыть архив")
                val res = stream.use { BackupManager.restoreFromZipStream(getApplication(), db, it) }
                reloadAll()
                _ui.update {
                    it.copy(
                        isBusy = false,
                        statusBanner = "Восстановлено профилей: ${res.profileCount}, записей: ${res.restoredRecords}, файлов: ${res.restoredAttachments}"
                    )
                }
            }.getOrElse { ex ->
                _ui.update { it.copy(isBusy = false, statusBanner = "Ошибка восстановления: ${ex.message}") }
            }
        }
    }

    fun importDevicePhoto(sourceUri: Uri) {
        val profile = _ui.value.activeProfile ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(isBusy = true) }
            runCatching {
                val (storedFile, _, origName) = MedicalDocumentEngine.copyUriToAttachments(
                    getApplication(),
                    sourceUri,
                    forcedMime = "image/jpeg"
                )
                val (ocrText, jpegBytes) = MedicalDocumentEngine.extractImageOcrAndJpeg(storedFile)
                val now = System.currentTimeMillis()
                var extractedVitals = SymptomAndClarifierEngine.extractBpAndPulseFromText(
                    rawText = ocrText,
                    profileId = profile.id,
                    trust = DataSourceTrust.VALIDATED_DEVICE,
                    sourceDetail = "Фото тонометра/часов (OCR)",
                    nowMillis = now,
                    attachmentPath = storedFile.absolutePath
                )

                val settings = aiSettings.value
                var aiNote = ""
                if (extractedVitals.isEmpty() && settings.isGeminiReady && jpegBytes != null) {
                    db.logAudit(profile.id, "AI_DEVICE_PHOTO", "Распознавание прибора в ${settings.geminiModel} (store=false)")
                    val reading = GeminiHealthClient.analyzeDevicePhoto(jpegBytes, ocrText, settings)
                    if (reading != null) {
                        val list = mutableListOf<VitalMeasurement>()
                        if (reading.systolic != null && reading.diastolic != null) {
                            list.add(
                                VitalMeasurement(
                                    id = UUID.randomUUID().toString(),
                                    profileId = profile.id,
                                    measuredAtMillis = now,
                                    type = VitalType.BLOOD_PRESSURE,
                                    value1 = reading.systolic.toDouble(),
                                    value2 = reading.diastolic.toDouble(),
                                    unit = VitalType.BLOOD_PRESSURE.defaultUnit,
                                    sourceTrust = DataSourceTrust.VALIDATED_DEVICE,
                                    sourceDetail = "Фото прибора (${settings.geminiModel})",
                                    attachmentPath = storedFile.absolutePath
                                )
                            )
                        }
                        if (reading.pulse != null) {
                            list.add(
                                VitalMeasurement(
                                    id = UUID.randomUUID().toString(),
                                    profileId = profile.id,
                                    measuredAtMillis = now,
                                    type = VitalType.HEART_RATE,
                                    value1 = reading.pulse.toDouble(),
                                    unit = VitalType.HEART_RATE.defaultUnit,
                                    sourceTrust = DataSourceTrust.VALIDATED_DEVICE,
                                    sourceDetail = "Фото прибора (${settings.geminiModel})",
                                    attachmentPath = storedFile.absolutePath
                                )
                            )
                        }
                        extractedVitals = list
                        aiNote = reading.note
                    }
                }

                if (extractedVitals.isNotEmpty()) {
                    extractedVitals.forEach { db.insertVital(it) }
                    val summary = extractedVitals.joinToString(", ") { v ->
                        if (v.value2 != null) "${v.type.titleRu} ${v.value1.toInt()}/${v.value2.toInt()}"
                        else "${v.type.titleRu} ${v.value1.toInt()}"
                    }
                    val doc = MedicalDocument(
                        id = UUID.randomUUID().toString(),
                        profileId = profile.id,
                        documentDateMillis = now,
                        createdAtMillis = now,
                        docType = DocType.DEVICE_PHOTO,
                        title = "Показания прибора: $summary",
                        doctorOrClinic = "Фото экрана ($origName)",
                        originalFilePath = storedFile.absolutePath,
                        mimeType = "image/jpeg",
                        ocrText = ocrText,
                        extractedSummary = listOfNotNull(summary, aiNote.takeIf { it.isNotBlank() }, ocrText.take(120).takeIf { it.isNotBlank() }).joinToString(" · "),
                        sourceTrust = DataSourceTrust.VALIDATED_DEVICE
                    )
                    db.upsertDocument(doc)
                    db.upsertEvent(
                        HealthEvent(
                            id = UUID.randomUUID().toString(),
                            profileId = profile.id,
                            timestampMillis = now,
                            category = HealthCategory.VITALS,
                            title = summary,
                            notes = doc.extractedSummary,
                            tags = listOf("Прибор", "Фото экрана"),
                            sourceTrust = DataSourceTrust.VALIDATED_DEVICE,
                            sourceDetail = extractedVitals.first().sourceDetail,
                            attachmentId = doc.id,
                            weatherSnapshotId = _ui.value.latestWeather?.id
                        )
                    )
                    val rf = RedFlagSafetyEngine.evaluate(
                        text = summary,
                        severity0to10 = null,
                        latestVitals = extractedVitals + _ui.value.vitals,
                        priorEvents = _ui.value.events,
                        nowMillis = now
                    )
                    db.logAudit(profile.id, "DEVICE_PHOTO_OCR", "Считано с фото: $summary")
                    reloadAll()
                    _ui.update { st ->
                        st.copy(
                            isBusy = false,
                            actionSuggestions = st.actionSuggestions.filterNot { it.key in setOf("measure_bp", "photo_tonometer") },
                            activeRedFlag = if (rf.isUrgent) rf else st.activeRedFlag,
                            statusBanner = "Считано с прибора: $summary"
                        )
                    }
                } else {
                    val doc = MedicalDocument(
                        id = UUID.randomUUID().toString(),
                        profileId = profile.id,
                        documentDateMillis = now,
                        createdAtMillis = now,
                        docType = DocType.DEVICE_PHOTO,
                        title = "Фото экрана прибора ($origName)",
                        doctorOrClinic = "Камера",
                        originalFilePath = storedFile.absolutePath,
                        mimeType = "image/jpeg",
                        ocrText = ocrText,
                        extractedSummary = ocrText.take(200).ifBlank { "Цифры на экране не распознаны автоматически. Введите давление вручную через + Прибор." },
                        sourceTrust = DataSourceTrust.USER_INPUT
                    )
                    db.upsertDocument(doc)
                    reloadAll()
                    _ui.update {
                        it.copy(
                            isBusy = false,
                            statusBanner = "Фото сохранено, но цифры АД/пульса не распознаны — введите вручную"
                        )
                    }
                }
            }.getOrElse { ex ->
                _ui.update { it.copy(isBusy = false, statusBanner = "Ошибка фото прибора: ${ex.message}") }
            }
        }
    }

    fun openEmergencyDispatcherCard() {
        val st = _ui.value
        val now = System.currentTimeMillis()
        val cardRu = RedFlagSafetyEngine.buildEmergencyDispatcherCard(
            profile = st.activeProfile,
            events = st.events,
            vitals = st.vitals,
            gpsLocationText = st.lastGpsText,
            lang = ReportLanguage.RU,
            nowMillis = now
        )
        val cardDe = RedFlagSafetyEngine.buildEmergencyDispatcherCard(
            profile = st.activeProfile,
            events = st.events,
            vitals = st.vitals,
            gpsLocationText = st.lastGpsText,
            lang = ReportLanguage.DE,
            nowMillis = now
        )
        _ui.update {
            it.copy(
                showEmergencyCardDialog = true,
                emergencyCardTextRu = cardRu,
                emergencyCardTextDe = cardDe
            )
        }
    }

    fun dismissEmergencyCardDialog() {
        _ui.update { it.copy(showEmergencyCardDialog = false) }
    }

    fun startSafetyCheckIn(minutes: Int) {
        val safeMinutes = minutes.coerceIn(1, 30)
        checkInJob?.cancel()
        _effects.tryEmit(UiEffect.SetCheckInAlarm(false))
        _ui.update {
            it.copy(
                checkInRemainingSeconds = safeMinutes * 60,
                checkInGraceSeconds = 0,
                statusBanner = "Таймер самоконтроля запущен на $safeMinutes мин"
            )
        }
        checkInJob = viewModelScope.launch {
            var remaining = safeMinutes * 60
            while (remaining > 0) {
                delay(1000L)
                remaining--
                _ui.update { it.copy(checkInRemainingSeconds = remaining) }
            }

            // Countdown elapsed -> 60s loud alarm & vibration grace period before auto-SMS
            var grace = 60
            _ui.update { it.copy(checkInRemainingSeconds = 0, checkInGraceSeconds = grace) }
            _effects.tryEmit(UiEffect.SetCheckInAlarm(true))

            while (grace > 0) {
                delay(1000L)
                grace--
                _ui.update { it.copy(checkInGraceSeconds = grace) }
            }

            // Grace expired without user cancellation -> stop buzzer, open 112 TTS card, send auto-SMS if consented
            _effects.tryEmit(UiEffect.SetCheckInAlarm(false))
            _ui.update { it.copy(checkInRemainingSeconds = 0, checkInGraceSeconds = 0) }

            val st = _ui.value
            val profile = st.activeProfile
            val now = System.currentTimeMillis()
            openEmergencyDispatcherCard()
            if (profile != null) {
                viewModelScope.launch(Dispatchers.IO) {
                    db.logAudit(profile.id, "CHECKIN_EXPIRED", "Таймер самоконтроля истёк без ответа")
                    reloadAll()
                }
                val phone = profile.emergencyContactPhone.trim()
                if (profile.autoSmsCheckInEnabled && phone.isNotBlank() && st.smsGranted) {
                    val ageText = profile.birthYear?.let { ", г.р. $it" }.orEmpty()
                    val addr = profile.homeAddress.trim().ifBlank { st.lastGpsText }
                    val lastComplaint = st.events.firstOrNull { it.category == HealthCategory.SYMPTOM }?.title ?: "ухудшение самочувствия"
                    val lastBp = st.vitals.firstOrNull { it.type == VitalType.BLOOD_PRESSURE }?.let {
                        "АД ${it.value1.toInt()}/${it.value2?.toInt() ?: "?"}"
                    }.orEmpty()
                    val smsText = buildString {
                        append("SOS ${AppMeta.APP_NAME}: ${profile.name}$ageText не ответил(а) на контрольный таймер! ")
                        append("Адрес/GPS: $addr. ")
                        append("Последнее: $lastComplaint")
                        if (lastBp.isNotBlank()) append(", $lastBp")
                        append(". Срочно свяжитесь или вызовите 112.")
                    }.take(300)
                    _effects.tryEmit(UiEffect.SendAutoEmergencySms(phone, smsText))
                } else {
                    _ui.update {
                        it.copy(
                            statusBanner = "Время контроля вышло! Открыта карточка 112 (включите авто-SMS в Профиле для автоотправки)"
                        )
                    }
                }
            }
        }
    }

    fun cancelSafetyCheckIn() {
        checkInJob?.cancel()
        checkInJob = null
        _effects.tryEmit(UiEffect.SetCheckInAlarm(false))
        _ui.update {
            it.copy(
                checkInRemainingSeconds = 0,
                checkInGraceSeconds = 0,
                statusBanner = "Контрольный таймер отключён — вы отметились в порядке"
            )
        }
    }

    fun checkForAppUpdate(silent: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            checkForAppUpdateInternal(silent)
        }
    }

    private fun checkForAppUpdateInternal(silent: Boolean) {
        _ui.update { it.copy(updateChecking = true) }
        val res = UpdateChecker.checkBlocking(getApplication())
        val found = res.getOrNull()
        if (found != null) {
            UpdateChecker.notifyIfNewUpdate(getApplication(), found)
        }
        _ui.update { st ->
            val msg = when {
                res.isFailure -> if (silent) st.statusBanner else "Ошибка проверки обновлений: ${res.exceptionOrNull()?.message}"
                found != null -> "Доступна новая версия v${found.name}"
                silent -> st.statusBanner
                else -> "Установлена актуальная версия v${st.installedVersionName}"
            }
            val note = when {
                res.isFailure -> "Проверка: ${res.exceptionOrNull()?.message ?: "нет связи"}"
                found != null -> "Доступно обновление v${found.name} (сборка ${found.code})"
                else -> "Версия v${st.installedVersionName} актуальна"
            }
            st.copy(
                updateChecking = false,
                availableUpdate = found,
                updateStatusNote = note,
                statusBanner = msg
            )
        }
    }

    fun downloadAndInstallUpdate() {
        val info = _ui.value.availableUpdate ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.update { it.copy(updateDownloadProgress = 0, statusBanner = "Скачивание v${info.name}...") }
            val res = UpdateChecker.downloadBlocking(getApplication(), info) { pct ->
                _ui.update { it.copy(updateDownloadProgress = pct) }
            }
            res.onSuccess { file ->
                _ui.update { it.copy(updateDownloadProgress = null, statusBanner = "Запуск установки v${info.name}...") }
                _effects.tryEmit(UiEffect.InstallUpdateApk(file))
            }.onFailure { ex ->
                _ui.update { it.copy(updateDownloadProgress = null, statusBanner = "Ошибка загрузки: ${ex.message}") }
            }
        }
    }

    private fun reloadAll() {
        val profiles = db.getProfiles().ifEmpty { listOf(db.ensureAtLeastOneProfile()) }
        val active = profiles.firstOrNull { it.id == prefs.activeProfileId } ?: profiles.first()
        prefs.activeProfileId = active.id

        val events = db.getEventsForProfile(active.id)
        val vitals = db.getVitalsForProfile(active.id)
        val intakes = db.getIntakesForProfile(active.id)
        val docs = db.getDocumentsForProfile(active.id)
        val labs = db.getLabMarkersForProfile(active.id)
        val weather = db.getWeatherSnapshots()
        val correlations = CorrelationEngine.analyzePatterns(events, vitals, intakes, weather)
        val labTrends = CorrelationEngine.summarizeLabTrends(labs)
        val audit = db.getAuditLogs(100)

        _ui.update {
            it.copy(
                profiles = profiles,
                activeProfile = active,
                events = events,
                vitals = vitals,
                intakes = intakes,
                documents = docs,
                labMarkers = labs,
                labTrends = labTrends,
                correlations = correlations,
                latestWeather = weather.firstOrNull(),
                weatherHistory = weather,
                auditLogs = audit
            )
        }
    }

    private fun scaleBitmap(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (w <= maxDim && h <= maxDim) return src
        val ratio = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(src, (w * ratio).toInt(), (h * ratio).toInt(), true)
    }
}
