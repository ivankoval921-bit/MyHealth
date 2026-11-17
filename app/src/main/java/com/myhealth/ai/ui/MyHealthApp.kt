package com.myhealth.ai.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.myhealth.ai.data.HealthPrefs
import com.myhealth.ai.logic.AppMeta
import com.myhealth.ai.logic.SensorCapabilityInfo
import com.myhealth.ai.model.AiSettings
import com.myhealth.ai.model.DataSourceTrust
import com.myhealth.ai.model.DocType
import com.myhealth.ai.model.HealthCategory
import com.myhealth.ai.model.HealthEvent
import com.myhealth.ai.model.HealthProfile
import com.myhealth.ai.model.IntakeKind
import com.myhealth.ai.model.MedicalDocument
import com.myhealth.ai.model.ReportLanguage
import com.myhealth.ai.model.VitalType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyHealthApp(
    viewModel: MainViewModel,
    onToggleVoice: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestSmsPermission: () -> Unit,
    onRefreshWeatherWithGps: () -> Unit,
    onPickMealPhotoCamera: () -> Unit,
    onPickMealPhotoGallery: () -> Unit,
    onPickDevicePhotoCamera: () -> Unit,
    onPickDevicePhotoGallery: () -> Unit,
    onImportDocCamera: (DocType) -> Unit,
    onImportDocFile: (DocType) -> Unit,
    onOpenDocumentFile: (MedicalDocument) -> Unit,
    onGenerateDoctorPdf: (Int, ReportLanguage) -> Unit,
    onExportBackupZip: () -> Unit,
    onRestoreBackupZip: () -> Unit,
    onDialEmergency: (String) -> Unit,
    onNotifyTrustedContact: (String, String) -> Unit,
    onSpeakEmergencyCard: (String, String) -> Unit,
    onStopSpeaking: () -> Unit,
    onOpenGeminiKeyPage: () -> Unit,
    onPasteGeminiKeyFromClipboard: () -> String?,
    onOpenGitHubRepo: () -> Unit
) {
    val ui by viewModel.ui.collectAsState()
    val ai by viewModel.aiSettings.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var showProfileMenu by remember { mutableStateOf(false) }
    var showVitalsDialog by remember { mutableStateOf(false) }
    var showIntakeDialog by remember { mutableStateOf(false) }
    var showMealChoiceDialog by remember { mutableStateOf(false) }
    var showDeviceChoiceDialog by remember { mutableStateOf(false) }
    var editingDoc by remember { mutableStateOf<MedicalDocument?>(null) }

    LaunchedEffect(ui.statusBanner) {
        if (ui.statusBanner.isNotBlank()) {
            snackbar.showSnackbar(ui.statusBanner)
            viewModel.clearBanner()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Моё здоровье", fontWeight = FontWeight.Bold)
                            Text(
                                "v${ui.installedVersionName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Box {
                            TextButton(
                                onClick = { showProfileMenu = true },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.height(22.dp)
                            ) {
                                Text(
                                    "${ui.activeProfile?.name ?: "..."} ▾ · ${AppMeta.DEVELOPER}",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            }
                            DropdownMenu(
                                expanded = showProfileMenu,
                                onDismissRequest = { showProfileMenu = false }
                            ) {
                                ui.profiles.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.name) },
                                        onClick = {
                                            showProfileMenu = false
                                            viewModel.switchProfile(p.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    if (ui.isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Button(
                        onClick = viewModel::openEmergencyDispatcherCard,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text("SOS 112", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = ui.activeTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        icon = { Icon(tabIconVector(tab), contentDescription = tab.titleRu, modifier = Modifier.size(22.dp)) },
                        label = { Text(tab.titleRu, maxLines = 1, softWrap = false) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ui.availableUpdate?.let { upd ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Доступно обновление v${upd.name}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                                if (upd.notes.isNotBlank()) {
                                    Text(upd.notes.take(100), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Button(
                                enabled = ui.updateDownloadProgress == null,
                                onClick = viewModel::downloadAndInstallUpdate,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(if (ui.updateDownloadProgress != null) "${ui.updateDownloadProgress}%" else "Обновить")
                            }
                        }
                        ui.updateDownloadProgress?.let { pct ->
                            LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            if (ui.checkInGraceSeconds > 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "ТРЕВОГА КОНТРОЛЯ: осталось ${ui.checkInGraceSeconds} с!",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onError
                        )
                        Text(
                            "Отметьтесь сейчас, иначе откроется голосовая карточка 112 и уйдёт авто-SMS доверенному контакту.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onError
                        )
                        Button(
                            onClick = viewModel::cancelSafetyCheckIn,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Я В ПОРЯДКЕ — ОТМЕНИТЬ", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else if (ui.checkInRemainingSeconds > 0) {
                val mins = ui.checkInRemainingSeconds / 60
                val secs = ui.checkInRemainingSeconds % 60
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Таймер контроля: ${"%02d:%02d".format(mins, secs)}",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = viewModel::openEmergencyDispatcherCard, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("112", maxLines = 1)
                            }
                            Button(onClick = viewModel::cancelSafetyCheckIn, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                Text("Я в порядке", maxLines = 1)
                            }
                        }
                    }
                }
            }

            ui.activeRedFlag?.let { rf ->
                val container = if (rf.isUrgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                val content = if (rf.isUrgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                Card(
                    colors = CardDefaults.cardColors(containerColor = container),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (rf.isUrgent) Icon(AppIcons.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Text(rf.headline, fontWeight = FontWeight.Bold, color = content)
                        }
                        Text(rf.reason, style = MaterialTheme.typography.bodySmall, color = content)
                        rf.actions.forEach { act ->
                            Text("• $act", style = MaterialTheme.typography.bodySmall, color = content)
                        }
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (rf.isUrgent) {
                                Button(
                                    onClick = { onDialEmergency(rf.callEmergencyNumber) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Позвонить ${rf.callEmergencyNumber}")
                                }
                                OutlinedButton(onClick = viewModel::openEmergencyDispatcherCard) {
                                    Text("Голос 112")
                                }
                                OutlinedButton(onClick = { viewModel.startSafetyCheckIn(3) }) {
                                    Text("Контроль 3 мин")
                                }
                            }
                            val phone = ui.activeProfile?.emergencyContactPhone.orEmpty()
                            if (phone.isNotBlank()) {
                                OutlinedButton(
                                    onClick = { onNotifyTrustedContact(phone, "Нужна помощь / проверьте меня (${rf.headline})") }
                                ) {
                                    Text("Близкому")
                                }
                            }
                            TextButton(onClick = viewModel::dismissRedFlag) { Text("Скрыть") }
                        }
                    }
                }
            }

            when (ui.activeTab) {
                MainTab.TODAY -> TodayTab(
                    ui = ui,
                    ai = ai,
                    onToggleVoice = onToggleVoice,
                    onRequestPermissions = onRequestPermissions,
                    onRefreshWeather = onRefreshWeatherWithGps,
                    onInputChange = viewModel::setInputText,
                    onLangChange = { tag -> viewModel.updateAiSettings(ai.copy(speechLangTag = tag)) },
                    onToggleSeverity = viewModel::toggleSeverity,
                    onToggleTag = viewModel::toggleTag,
                    onSubmit = viewModel::submitQuickHealthEntry,
                    onAnswerQuestion = viewModel::answerClarifyingQuestion,
                    onDismissClarifying = viewModel::dismissClarifyingCard,
                    onOpenVitalsDialog = { showVitalsDialog = true },
                    onOpenIntakeDialog = { showIntakeDialog = true },
                    onOpenMealPhoto = { showMealChoiceDialog = true },
                    onOpenDevicePhoto = { showDeviceChoiceDialog = true },
                    onDismissSuggestion = viewModel::dismissActionSuggestion,
                    onStartCheckIn = viewModel::startSafetyCheckIn,
                    onOpenEmergencyCard = viewModel::openEmergencyDispatcherCard,
                    onQuickWater = { viewModel.logQuickIntake(IntakeKind.WATER, "Вода", 250.0, "мл") },
                    onQuickCoffee = { viewModel.logQuickIntake(IntakeKind.COFFEE, "Кофе", 1.0, "чаш.") }
                )

                MainTab.HISTORY -> HistoryTab(
                    ui = ui,
                    onFilterChange = viewModel::setHistoryFilter,
                    onDeleteEvent = viewModel::deleteEvent
                )

                MainTab.ANALYTICS -> AnalyticsTab(
                    ui = ui,
                    ai = ai,
                    onGenerateAiBrief = viewModel::generateAiDoctorReflection
                )

                MainTab.DOCS -> DocsAndPdfTab(
                    ui = ui,
                    onImportCamera = onImportDocCamera,
                    onImportFile = onImportDocFile,
                    onOpenFile = onOpenDocumentFile,
                    onEditDoc = { editingDoc = it },
                    onDeleteDoc = viewModel::deleteDocument,
                    onGeneratePdf = onGenerateDoctorPdf
                )

                MainTab.PROFILE -> ProfileAndPrivacyTab(
                    ui = ui,
                    ai = ai,
                    onSaveProfile = viewModel::saveProfile,
                    onCreateProfile = { name ->
                        viewModel.saveProfile(
                            HealthProfile(
                                id = UUID.randomUUID().toString(),
                                name = name.trim().ifBlank { "Член семьи" }
                            )
                        )
                    },
                    onUpdateAi = viewModel::updateAiSettings,
                    onTestGemini = viewModel::testGeminiConnection,
                    onOpenGeminiKeyPage = onOpenGeminiKeyPage,
                    onPasteGeminiKey = onPasteGeminiKeyFromClipboard,
                    onExportBackup = onExportBackupZip,
                    onRestoreBackup = onRestoreBackupZip,
                    onRequestSmsPermission = onRequestSmsPermission,
                    onCheckForUpdate = { viewModel.checkForAppUpdate(silent = false) },
                    onDownloadUpdate = viewModel::downloadAndInstallUpdate,
                    onOpenGitHubRepo = onOpenGitHubRepo
                )
            }
        }
    }

    if (showDeviceChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showDeviceChoiceDialog = false },
            title = { Text("Фото экрана тонометра / часов") },
            text = {
                Text("Наведите камеру на экран прибора — система считает давление и пульс через OCR (и Gemini Vision, если он включён).")
            },
            confirmButton = {
                Button(onClick = {
                    showDeviceChoiceDialog = false
                    onPickDevicePhotoCamera()
                }) { Text("Камера") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        showDeviceChoiceDialog = false
                        onPickDevicePhotoGallery()
                    }) { Text("Из галереи") }
                    TextButton(onClick = { showDeviceChoiceDialog = false }) { Text("Отмена") }
                }
            }
        )
    }

    if (ui.showEmergencyCardDialog) {
        EmergencyDispatcherDialog(
            ui = ui,
            onDismiss = {
                onStopSpeaking()
                viewModel.dismissEmergencyCardDialog()
            },
            onSpeak = onSpeakEmergencyCard,
            onStopSpeaking = onStopSpeaking,
            onDialEmergency = { onDialEmergency("112") },
            onNotifyContact = onNotifyTrustedContact
        )
    }

    if (showVitalsDialog) {
        QuickVitalsDialog(
            onDismiss = { showVitalsDialog = false },
            onSave = { type, v1, v2, ctx, trust ->
                viewModel.logQuickVital(type, v1, v2, ctx, trust)
                showVitalsDialog = false
            }
        )
    }

    if (showIntakeDialog) {
        QuickIntakeDialog(
            onDismiss = { showIntakeDialog = false },
            onSave = { kind, name, amt, unit, notes ->
                viewModel.logQuickIntake(kind, name, amt, unit, notes)
                showIntakeDialog = false
            }
        )
    }

    if (showMealChoiceDialog) {
        AlertDialog(
            onDismissRequest = { showMealChoiceDialog = false },
            title = { Text("Фото блюда (оценка БЖУ и триггеров)") },
            text = {
                Text(
                    if (ai.isGeminiReady) "Фотография будет оценена через Gemini (${ai.geminiModel}, store=false) и сохранена в дневник."
                    else "Gemini не включён — фото сохранится локально. Вы можете включить ИИ в разделе Профиль."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showMealChoiceDialog = false
                    onPickMealPhotoCamera()
                }) { Text("Камера") }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        showMealChoiceDialog = false
                        onPickMealPhotoGallery()
                    }) { Text("Из галереи") }
                    TextButton(onClick = { showMealChoiceDialog = false }) { Text("Отмена") }
                }
            }
        )
    }

    editingDoc?.let { doc ->
        EditDocumentDialog(
            doc = doc,
            onDismiss = { editingDoc = null },
            onSave = { t, clinic, sum ->
                viewModel.updateDocumentManualCorrection(doc, t, clinic, sum)
                editingDoc = null
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TodayTab(
    ui: MyHealthUiState,
    ai: AiSettings,
    onToggleVoice: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRefreshWeather: () -> Unit,
    onInputChange: (String) -> Unit,
    onLangChange: (String) -> Unit,
    onToggleSeverity: (Int) -> Unit,
    onToggleTag: (String) -> Unit,
    onSubmit: () -> Unit,
    onAnswerQuestion: (com.myhealth.ai.model.ClarifyingQuestion, String) -> Unit,
    onDismissClarifying: () -> Unit,
    onOpenVitalsDialog: () -> Unit,
    onOpenIntakeDialog: () -> Unit,
    onOpenMealPhoto: () -> Unit,
    onOpenDevicePhoto: () -> Unit,
    onDismissSuggestion: (String) -> Unit,
    onStartCheckIn: (Int) -> Unit,
    onOpenEmergencyCard: () -> Unit,
    onQuickWater: () -> Unit,
    onQuickCoffee: () -> Unit
) {
    val quickTags = listOf(
        "Мигрень / Голова", "Давление", "Изжога/ЖКТ", "Аллергия",
        "Усталость", "Стресс", "Недосып", "Кофе", "Вода", "Алкоголь", "Таблетка"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (!ui.micGranted || !ui.cameraGranted) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Для голоса, фото и погоды по GPS выдайте права",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = onRequestPermissions) { Text("Разрешить") }
                    }
                }
            }
        }

        // Weather & Barometric Pressure Card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Погода и давление (Open-Meteo)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(
                            ui.latestWeather?.summaryText ?: "Нажмите «Обновить» для подтяжки давления и AQI",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    OutlinedButton(onClick = onRefreshWeather, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text("Обновить")
                    }
                }
            }
        }

        // Voice + 2-tap capture card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onToggleVoice,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (ui.isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                    ) {
                        Icon(if (ui.isListening) AppIcons.Stop else AppIcons.Mic, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (ui.isListening) "Стоп запись" else "Надиктовать состояние",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Язык диктовки:", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("ru-RU" to "RU", "uk-UA" to "UK", "de-DE" to "DE").forEach { (tag, label) ->
                                FilterChip(
                                    selected = ai.speechLangTag == tag,
                                    onClick = { onLangChange(tag) },
                                    label = { Text(label, maxLines = 1, softWrap = false) }
                                )
                            }
                        }
                    }

                    if (ui.partialSpeechText.isNotBlank()) {
                        Text("Слышу: ${ui.partialSpeechText}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }

                    OutlinedTextField(
                        value = ui.inputText,
                        onValueChange = onInputChange,
                        label = { Text("Самочувствие, давление, кофе, еда или лекарство") },
                        placeholder = { Text("Напр.: болит голова на 6/10, давление 138/88, выпил кофе") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(104.dp)
                    )

                    Text("Тяжесть / дискомфорт (0–10):", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        (1..10).forEach { sev ->
                            FilterChip(
                                selected = ui.selectedSeverity == sev,
                                onClick = { onToggleSeverity(sev) },
                                label = { Text("$sev") }
                            )
                        }
                    }

                    Text("Быстрые теги (2 тапа):", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        quickTags.forEach { tag ->
                            FilterChip(
                                selected = tag in ui.selectedTags,
                                onClick = { onToggleTag(tag) },
                                label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Button(
                        onClick = onSubmit,
                        enabled = !ui.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (ai.isGeminiReady) "Сохранить + разобрать (Gemini)" else "Сохранить в дневник",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // AI guidance note if present
        if (ui.aiGuidanceNote.isNotBlank()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Наблюдение ассистента (не диагноз):", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Text(ui.aiGuidanceNote, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Proactive next diagnostic checks ("measure BP/pulse right now", photo tonometer)
        if (ui.actionSuggestions.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Рекомендуемый шаг прямо сейчас:", fontWeight = FontWeight.Bold)
                        ui.actionSuggestions.forEach { act ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("• ${act.titleRu}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(act.reasonRu, style = MaterialTheme.typography.bodySmall)
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    when (act.key) {
                                        "measure_bp", "measure_temp" -> {
                                            Button(onClick = onOpenVitalsDialog, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                                Text(if (act.key == "measure_bp") "+ Ввести АД/пульс" else "+ Ввести t°C")
                                            }
                                            if (act.key == "measure_bp") {
                                                OutlinedButton(onClick = onOpenDevicePhoto, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                                    Text("📷 Фото тонометра")
                                                }
                                            }
                                        }
                                        "photo_tonometer" -> {
                                            Button(onClick = onOpenDevicePhoto, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                                Text("📷 Снять экран прибора")
                                            }
                                        }
                                        else -> {
                                            OutlinedButton(onClick = onOpenVitalsDialog, contentPadding = PaddingValues(horizontal = 10.dp)) {
                                                Text("+ Записать замер")
                                            }
                                        }
                                    }
                                    TextButton(onClick = { onDismissSuggestion(act.key) }) { Text("Скрыть") }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Clarifying questions card
        if (ui.activeQuestions.isNotEmpty() && ui.activeClarifyingEvent != null) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Уточнения для точности:",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = onDismissClarifying) {
                                Text("Готово", maxLines = 1, softWrap = false)
                            }
                        }
                        ui.activeQuestions.forEach { q ->
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(q.promptRu, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    q.quickOptions.forEach { opt ->
                                        AssistChip(
                                            onClick = { onAnswerQuestion(q, opt) },
                                            label = { Text(opt) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Quick measurements & intakes grid
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Быстрые замеры, питание и привычки", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = onOpenVitalsDialog,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("+ Прибор / Сон", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = onOpenDevicePhoto,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("📷 Тонометр", maxLines = 1, softWrap = false)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = onOpenIntakeDialog,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("+ Еда / Лекарство", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = onOpenMealPhoto,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Фото еды", maxLines = 1, softWrap = false)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = onQuickWater,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("+ Вода (250 мл)", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = onQuickCoffee,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("+ Кофе (1 чаш.)", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        // Check-In Safety Timer & 112 Voice Card
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Контроль самочувствия и карточка 112", fontWeight = FontWeight.Bold)
                    Text(
                        "Если стало плохо — запустите таймер: если не отметиться, включится сирена (60 с), карточка для диспетчера 112 и авто-SMS близкому.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(1, 3, 5).forEach { mins ->
                            OutlinedButton(
                                onClick = { onStartCheckIn(mins) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text("⏱ $mins м", maxLines = 1, softWrap = false)
                            }
                        }
                        Button(
                            onClick = onOpenEmergencyCard,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1.25f),
                            contentPadding = PaddingValues(horizontal = 6.dp)
                        ) {
                            Text("112 Голос", maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }

        // Recent 3 timeline preview
        if (ui.events.isNotEmpty()) {
            item {
                Text("Последние записи:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }
            items(ui.events.take(4), key = { it.id }) { ev ->
                EventRowCard(ev = ev, onDelete = null)
            }
        }
    }
}

@Composable
private fun HistoryTab(
    ui: MyHealthUiState,
    onFilterChange: (String, HealthCategory?) -> Unit,
    onDeleteEvent: (String) -> Unit
) {
    val q = ui.historySearchQuery.trim()
    val filtered = ui.events.filter { ev ->
        val catOk = ui.historyCategoryFilter == null || ev.category == ui.historyCategoryFilter
        val textOk = q.isEmpty() ||
            ev.title.contains(q, ignoreCase = true) ||
            ev.notes.contains(q, ignoreCase = true) ||
            ev.clarifyingQaSummary.contains(q, ignoreCase = true) ||
            ev.tags.any { it.contains(q, ignoreCase = true) }
        catOk && textOk
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = ui.historySearchQuery,
            onValueChange = { onFilterChange(it, ui.historyCategoryFilter) },
            label = { Text("Поиск по симптомам, лекарствам, тегам") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                selected = ui.historyCategoryFilter == null,
                onClick = { onFilterChange(ui.historySearchQuery, null) },
                label = { Text("Все (${ui.events.size})") }
            )
            HealthCategory.entries.forEach { cat ->
                FilterChip(
                    selected = ui.historyCategoryFilter == cat,
                    onClick = { onFilterChange(ui.historySearchQuery, cat) },
                    label = { Text(cat.titleRu) }
                )
            }
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (filtered.isEmpty()) {
                item {
                    Text("Подходящих записей не найдено.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                items(filtered, key = { it.id }) { ev ->
                    EventRowCard(ev = ev, onDelete = { onDeleteEvent(ev.id) })
                }
            }
        }
    }
}

@Composable
private fun AnalyticsTab(
    ui: MyHealthUiState,
    ai: AiSettings,
    onGenerateAiBrief: () -> Unit
) {
    val bpList = ui.vitals.filter { it.type == VitalType.BLOOD_PRESSURE }
    val sleepList = ui.vitals.filter { it.type == VitalType.SLEEP_HOURS }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Сводка базы здоровья (${ui.activeProfile?.name ?: ""})", fontWeight = FontWeight.Bold)
                    Text("Записей в хронике: ${ui.events.size} · Замеров: ${ui.vitals.size} · Питания/привычек: ${ui.intakes.size} · Документов: ${ui.documents.size}")
                    if (bpList.isNotEmpty()) {
                        val avgSys = bpList.map { it.value1 }.average().toInt()
                        val avgDia = bpList.mapNotNull { it.value2 }.average().toInt()
                        Text("Среднее давление (${bpList.size} замеров): $avgSys/$avgDia мм рт.ст.", style = MaterialTheme.typography.bodySmall)
                    }
                    if (sleepList.isNotEmpty()) {
                        val avgSleep = String.format(Locale.US, "%.1f", sleepList.map { it.value1 }.average())
                        Text("Средний сон (${sleepList.size} ночей): $avgSleep ч", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Text("Закономерности и триггеры (статистика ≠ диагноз)", fontWeight = FontWeight.Bold)
        }

        if (ui.correlations.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Продолжайте вести дневник: когда наберётся от 2 и более схожих событий (головная боль, изжога, скачки давления) вместе с погодой, сном или привычками, здесь появятся расчётные закономерности с указанием уверенности.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            items(ui.correlations, key = { it.id }) { c ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${c.tier.titleRu} · ${c.coOccurrencePct}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(c.summaryText, fontWeight = FontWeight.SemiBold)
                        Text(c.confidenceLabel, style = MaterialTheme.typography.bodySmall)
                        Text("Альтернативные факторы: ${c.alternativeExplanations}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (ui.labTrends.isNotEmpty()) {
            item {
                Text("Динамика лабораторных показателей", fontWeight = FontWeight.Bold)
            }
            items(ui.labTrends, key = { it.markerName }) { lt ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(lt.markerName, fontWeight = FontWeight.Bold)
                            Text("${lt.deltaText} · замеров: ${lt.count}", style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("${lt.latestValue} ${lt.unit}", fontWeight = FontWeight.Bold)
                            Text(lt.flag.titleRu, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Подготовка к визиту врача (Gemini)", fontWeight = FontWeight.Bold)
                    Button(
                        onClick = onGenerateAiBrief,
                        enabled = ai.isGeminiReady && !ui.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (ai.isGeminiReady) "✨ Сформировать сводку и вопросы врачу" else "Включите Gemini в Профиле")
                    }
                    if (ui.aiDoctorBriefText.isNotBlank()) {
                        Text(ui.aiDoctorBriefText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DocsAndPdfTab(
    ui: MyHealthUiState,
    onImportCamera: (DocType) -> Unit,
    onImportFile: (DocType) -> Unit,
    onOpenFile: (MedicalDocument) -> Unit,
    onEditDoc: (MedicalDocument) -> Unit,
    onDeleteDoc: (MedicalDocument) -> Unit,
    onGeneratePdf: (Int, ReportLanguage) -> Unit
) {
    var selectedDocType by remember { mutableStateOf(DocType.LAB_RESULT) }
    var reportDays by remember { mutableStateOf(30) }
    var reportLang by remember { mutableStateOf(ui.activeProfile?.preferredReportLang ?: ReportLanguage.RU) }
    val dtFmt = remember { SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Импорт документов, анализов и фото", fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DocType.entries.forEach { dt ->
                            FilterChip(
                                selected = selectedDocType == dt,
                                onClick = { selectedDocType = dt },
                                label = { Text(dt.titleRu, maxLines = 1) }
                            )
                        }
                    }

                    if (selectedDocType == DocType.EYE_OR_SKIN_PHOTO) {
                        Text(
                            "Примечание: фото глаз и кожи сохраняются для наблюдения видимых изменений и консультации офтальмолога/дерматолога (не является иридодиагностикой внутренних органов).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { onImportCamera(selectedDocType) }, modifier = Modifier.weight(1f)) {
                            Text("📷 Камера")
                        }
                        OutlinedButton(onClick = { onImportFile(selectedDocType) }, modifier = Modifier.weight(1f)) {
                            Text("📄 PDF / Фото")
                        }
                    }
                }
            }
        }

        // PDF Doctor Report Export Card
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Выписка для врача (PDF)", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(14 to "14 д", 30 to "30 д", 90 to "90 д", 365 to "1 год").forEach { (d, lbl) ->
                            FilterChip(
                                selected = reportDays == d,
                                onClick = { reportDays = d },
                                label = { Text(lbl, maxLines = 1, softWrap = false) }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReportLanguage.entries.forEach { l ->
                            FilterChip(
                                selected = reportLang == l,
                                onClick = { reportLang = l },
                                label = { Text(l.titleRu) }
                            )
                        }
                    }
                    Button(
                        onClick = { onGeneratePdf(reportDays, reportLang) },
                        enabled = !ui.isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("📋 Сформировать и открыть PDF (${reportLang.code})", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Text("Архив документов (${ui.documents.size})", fontWeight = FontWeight.Bold)
        }

        if (ui.documents.isEmpty()) {
            item {
                Text("Документы ещё не добавлены. Поддерживаются PDF-анализы, выписки ePA, фото рецептов и приборов.", style = MaterialTheme.typography.bodySmall)
            }
        } else {
            items(ui.documents, key = { it.id }) { doc ->
                val markers = ui.labMarkers.filter { it.documentId == doc.id }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${dtFmt.format(Date(doc.documentDateMillis))} · ${doc.docType.titleRu}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(doc.title, fontWeight = FontWeight.Bold)
                        if (doc.doctorOrClinic.isNotBlank()) {
                            Text("Источник: ${doc.doctorOrClinic}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (doc.extractedSummary.isNotBlank()) {
                            Text(doc.extractedSummary, style = MaterialTheme.typography.bodySmall)
                        }
                        if (markers.isNotEmpty()) {
                            Text(
                                "Показатели: " + markers.joinToString(" | ") { "${it.markerName}: ${it.value} ${it.unit} (${it.flag.titleRu})" },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onOpenFile(doc) }) { Text("Открыть") }
                            TextButton(onClick = { onEditDoc(doc) }) { Text("Исправить") }
                            TextButton(onClick = { onDeleteDoc(doc) }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileAndPrivacyTab(
    ui: MyHealthUiState,
    ai: AiSettings,
    onSaveProfile: (HealthProfile) -> Unit,
    onCreateProfile: (String) -> Unit,
    onUpdateAi: (AiSettings) -> Unit,
    onTestGemini: () -> Unit,
    onOpenGeminiKeyPage: () -> Unit,
    onPasteGeminiKey: () -> String?,
    onExportBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onRequestSmsPermission: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onOpenGitHubRepo: () -> Unit
) {
    val active = ui.activeProfile
    var name by remember(active?.id) { mutableStateOf(active?.name.orEmpty()) }
    var birthYear by remember(active?.id) { mutableStateOf(active?.birthYear?.toString().orEmpty()) }
    var chronic by remember(active?.id) { mutableStateOf(active?.chronicConditions.orEmpty()) }
    var meds by remember(active?.id) { mutableStateOf(active?.medications.orEmpty()) }
    var allergies by remember(active?.id) { mutableStateOf(active?.allergies.orEmpty()) }
    var emName by remember(active?.id) { mutableStateOf(active?.emergencyContactName.orEmpty()) }
    var emPhone by remember(active?.id) { mutableStateOf(active?.emergencyContactPhone.orEmpty()) }
    var homeAddress by remember(active?.id) { mutableStateOf(active?.homeAddress.orEmpty()) }
    var autoSmsCheckIn by remember(active?.id) { mutableStateOf(active?.autoSmsCheckInEnabled ?: false) }
    var repLang by remember(active?.id) { mutableStateOf(active?.preferredReportLang ?: ReportLanguage.RU) }

    var newProfileName by remember { mutableStateOf("") }
    var keyDraft by remember(ai.geminiApiKey) { mutableStateOf(ai.geminiApiKey) }
    var showAudit by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Developer attribution, Version & GitHub Auto-Update
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("О программе и обновления", fontWeight = FontWeight.Bold)
                    Text("${AppMeta.APP_NAME} (${AppMeta.APP_EN_NAME}) · версия v${ui.installedVersionName} (сборка ${ui.installedVersionCode})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("Разработчик: ${AppMeta.DEVELOPER}", style = MaterialTheme.typography.bodySmall)
                    Text("GitHub: ${AppMeta.GITHUB_REPO}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    if (ui.updateStatusNote.isNotBlank()) {
                        Text(ui.updateStatusNote, style = MaterialTheme.typography.bodySmall)
                    }
                    ui.updateDownloadProgress?.let { pct ->
                        LinearProgressIndicator(progress = { pct / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = onCheckForUpdate,
                            enabled = !ui.updateChecking && ui.updateDownloadProgress == null,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(if (ui.updateChecking) "Проверка..." else "Проверить обновление", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = onOpenGitHubRepo,
                            modifier = Modifier.weight(0.75f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("GitHub", maxLines = 1, softWrap = false)
                        }
                    }
                    ui.availableUpdate?.let { upd ->
                        Button(
                            onClick = onDownloadUpdate,
                            enabled = ui.updateDownloadProgress == null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Скачать и установить v${upd.name}")
                        }
                    }
                }
            }
        }
        // Gemini API & Consent first for quick turnkey configuration
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Gemini ИИ и конфиденциальность", fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Согласие на отправку запросов в Gemini", fontWeight = FontWeight.Medium)
                            Text(
                                "Используется режим store=false. Без согласия приложение работает полностью локально.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = ai.aiConsentGranted,
                            onCheckedChange = { onUpdateAi(ai.copy(aiConsentGranted = it)) }
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        HealthPrefs.SUPPORTED_GEMINI_MODELS.forEach { m ->
                            FilterChip(
                                selected = ai.geminiModel == m,
                                onClick = { onUpdateAi(ai.copy(geminiModel = m)) },
                                label = { Text(m.removePrefix("gemini-"), maxLines = 1, softWrap = false) }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = keyDraft,
                        onValueChange = { keyDraft = it },
                        label = { Text("Gemini API Key (AIza...)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = onOpenGeminiKeyPage,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Ключ AI Studio", maxLines = 1, softWrap = false)
                        }
                        OutlinedButton(
                            onClick = {
                                val pasted = onPasteGeminiKey()
                                if (!pasted.isNullOrBlank()) keyDraft = pasted.trim()
                            },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Из буфера", maxLines = 1, softWrap = false)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { onUpdateAi(ai.copy(geminiApiKey = keyDraft.trim())) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) { Text("Сохранить ключ", maxLines = 1, softWrap = false) }
                        OutlinedButton(
                            onClick = onTestGemini,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Проверить", maxLines = 1, softWrap = false)
                        }
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Геомагнитный Kp-индекс (NOAA)", style = MaterialTheme.typography.bodyMedium)
                            Text("Подтягивать справочно вместе с погодой", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(
                            checked = ai.includeGeomagneticRef,
                            onCheckedChange = { onUpdateAi(ai.copy(includeGeomagneticRef = it)) }
                        )
                    }
                }
            }
        }

        // Profile details
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Карта профиля семьи", fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Имя") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = birthYear,
                        onValueChange = { birthYear = it.filter { ch -> ch.isDigit() }.take(4) },
                        label = { Text("Год рождения") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(value = chronic, onValueChange = { chronic = it }, label = { Text("Хронические состояния") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = meds, onValueChange = { meds = it }, label = { Text("Постоянные лекарства") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = allergies, onValueChange = { allergies = it }, label = { Text("Аллергии / непереносимость") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = emName, onValueChange = { emName = it }, label = { Text("Доверенный контакт (имя)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = emPhone,
                        onValueChange = { emPhone = it },
                        label = { Text("Телефон контакта для экстренного SMS") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = homeAddress,
                        onValueChange = { homeAddress = it },
                        label = { Text("Домашний адрес (для карточки 112 и SMS)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Авто-SMS контакту при пропуске таймера", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (ui.smsGranted) "Право SEND_SMS выдано. В Германии обычные SMS на 112 не принимаются — авто-SMS уйдёт доверенному контакту."
                                else "Нажмите для запроса права SEND_SMS (отправка только если не отключен контрольный таймер).",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Switch(
                            checked = autoSmsCheckIn,
                            onCheckedChange = { enabled ->
                                autoSmsCheckIn = enabled
                                if (enabled && !ui.smsGranted) onRequestSmsPermission()
                            }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Язык выписки:", style = MaterialTheme.typography.bodySmall)
                        ReportLanguage.entries.forEach { rl ->
                            FilterChip(selected = repLang == rl, onClick = { repLang = rl }, label = { Text(rl.titleRu) })
                        }
                    }
                    Button(
                        onClick = {
                            if (active != null) {
                                onSaveProfile(
                                    active.copy(
                                        name = name.trim().ifBlank { active.name },
                                        birthYear = birthYear.toIntOrNull(),
                                        chronicConditions = chronic.trim(),
                                        medications = meds.trim(),
                                        allergies = allergies.trim(),
                                        emergencyContactName = emName.trim(),
                                        emergencyContactPhone = emPhone.trim(),
                                        homeAddress = homeAddress.trim(),
                                        autoSmsCheckInEnabled = autoSmsCheckIn,
                                        preferredReportLang = repLang
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Сохранить профиль") }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newProfileName,
                            onValueChange = { newProfileName = it },
                            label = { Text("Новый профиль семьи") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Button(onClick = {
                            if (newProfileName.isNotBlank()) {
                                onCreateProfile(newProfileName)
                                newProfileName = ""
                            }
                        }) { Text("+ Добавить") }
                    }
                }
            }
        }

        // Backup & Roadmap & Audit log
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Резервное копирование и перенос", fontWeight = FontWeight.Bold)
                    Text("Все данные, фото и PDF упаковываются в единый ZIP-архив.", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onExportBackup, modifier = Modifier.weight(1f)) { Text("💾 Экспорт ZIP") }
                        OutlinedButton(onClick = onRestoreBackup, modifier = Modifier.weight(1f)) { Text("📥 Восстановить") }
                    }
                    TextButton(onClick = { showAudit = !showAudit }) {
                        Text(if (showAudit) "Скрыть журнал доступа (${ui.auditLogs.size})" else "Показать журнал доступа (${ui.auditLogs.size})")
                    }
                    if (showAudit) {
                        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.GERMANY)
                        ui.auditLogs.take(15).forEach { log ->
                            Text("${fmt.format(Date(log.timestampMillis))} [${log.action}] ${log.details}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Датчики, часы и следующие этапы", fontWeight = FontWeight.Bold)
                    Text("• ${SensorCapabilityInfo.WATCH_ON_DEMAND_NOTE}", style = MaterialTheme.typography.bodySmall)
                    Text("• Этап 2: Android Health Connect и Bluetooth BLE (умные часы: сон, шаги, пульс, HRV, SpO₂).", style = MaterialTheme.typography.bodySmall)
                    Text("• Этап 3: многолетние тренды анализов, расширенный шлюз ePA и связь с «Личным Секретарём».", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun EmergencyDispatcherDialog(
    ui: MyHealthUiState,
    onDismiss: () -> Unit,
    onSpeak: (String, String) -> Unit,
    onStopSpeaking: () -> Unit,
    onDialEmergency: () -> Unit,
    onNotifyContact: (String, String) -> Unit
) {
    var selectedLang by remember { mutableStateOf(ReportLanguage.DE) }
    val cardText = if (selectedLang == ReportLanguage.DE) ui.emergencyCardTextDe else ui.emergencyCardTextRu
    val ttsLang = if (selectedLang == ReportLanguage.DE) "de-DE" else "ru-RU"
    val contactPhone = ui.activeProfile?.emergencyContactPhone.orEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Карточка для диспетчера 112", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedLang == ReportLanguage.DE,
                        onClick = { selectedLang = ReportLanguage.DE },
                        label = { Text("DE (Германия)", maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = selectedLang == ReportLanguage.RU,
                        onClick = { selectedLang = ReportLanguage.RU },
                        label = { Text("RU (Русский)", maxLines = 1, softWrap = false) }
                    )
                }
                Text(
                    "Нажмите «Озвучить вслух» рядом с микрофоном/громкой связью при звонке 112. " +
                        "Android не позволяет сторонним программам тайно звонить на 112 или встраивать речь внутрь GSM-вызова.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(
                        cardText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onSpeak(cardText, ttsLang) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("🔊 Озвучить (${selectedLang.name})", maxLines = 1)
                    }
                    OutlinedButton(onClick = onStopSpeaking, contentPadding = PaddingValues(horizontal = 10.dp)) {
                        Text("⏹ Стоп")
                    }
                }
                if (contactPhone.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            onNotifyContact(
                                contactPhone,
                                "Срочно проверьте меня! ${ui.activeProfile?.homeAddress.orEmpty()} (${ui.lastGpsText})"
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("💬 Открыть SMS близкому ($contactPhone)")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDialEmergency,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("📞 Позвонить 112")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@Composable
private fun EventRowCard(ev: HealthEvent, onDelete: (() -> Unit)?) {
    val fmt = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${fmt.format(Date(ev.timestampMillis))} · ${ev.category.titleRu}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text("[${ev.sourceTrust.badgeRu}]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(ev.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                ev.severity0to10?.let { sev ->
                    Text("$sev/10", fontWeight = FontWeight.Bold, color = if (sev >= 7) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
            }
            if (ev.notes.isNotBlank() && ev.notes != ev.title) {
                Text(ev.notes, style = MaterialTheme.typography.bodySmall)
            }
            if (ev.clarifyingQaSummary.isNotBlank()) {
                Text("Уточнения: ${ev.clarifyingQaSummary}", style = MaterialTheme.typography.bodySmall)
            }
            if (ev.tags.isNotEmpty()) {
                Text(ev.tags.joinToString(" · ") { "#$it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onDelete != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text("Удалить", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickVitalsDialog(
    onDismiss: () -> Unit,
    onSave: (VitalType, Double, Double?, String, DataSourceTrust) -> Unit
) {
    var selectedType by remember { mutableStateOf(VitalType.BLOOD_PRESSURE) }
    var val1 by remember { mutableStateOf("") }
    var val2 by remember { mutableStateOf("") }
    var contextTag by remember { mutableStateOf("") }
    var isWearable by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить замер") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        VitalType.BLOOD_PRESSURE,
                        VitalType.HEART_RATE,
                        VitalType.SLEEP_HOURS,
                        VitalType.WEIGHT_KG,
                        VitalType.TEMPERATURE_C,
                        VitalType.GLUCOSE_MGDL,
                        VitalType.SPO2_PCT,
                        VitalType.URINE_PH,
                        VitalType.STEPS
                    ).forEach { vt ->
                        FilterChip(
                            selected = selectedType == vt,
                            onClick = { selectedType = vt },
                            label = { Text(vt.titleRu, maxLines = 1) }
                        )
                    }
                }
                OutlinedTextField(
                    value = val1,
                    onValueChange = { val1 = it },
                    label = {
                        Text(
                            if (selectedType == VitalType.BLOOD_PRESSURE) "Верхнее / систолическое"
                            else "Значение (${selectedType.defaultUnit})"
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (selectedType == VitalType.BLOOD_PRESSURE) {
                    OutlinedTextField(
                        value = val2,
                        onValueChange = { val2 = it },
                        label = { Text("Нижнее / диастолическое") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = contextTag,
                    onValueChange = { contextTag = it },
                    label = { Text("Контекст (утро, покой, после спорта)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Это ориентир с умных часов?", style = MaterialTheme.typography.bodySmall)
                    Switch(checked = isWearable, onCheckedChange = { isWearable = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val n1 = val1.replace(',', '.').toDoubleOrNull() ?: return@Button
                val n2 = if (selectedType == VitalType.BLOOD_PRESSURE) val2.replace(',', '.').toDoubleOrNull() else null
                val trust = if (isWearable) DataSourceTrust.WEARABLE_SENSOR else DataSourceTrust.VALIDATED_DEVICE
                onSave(selectedType, n1, n2, contextTag.trim(), trust)
            }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun QuickIntakeDialog(
    onDismiss: () -> Unit,
    onSave: (IntakeKind, String, Double?, String, String) -> Unit
) {
    var kind by remember { mutableStateOf(IntakeKind.MEDICATION) }
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("табл.") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Еда, напитки, алкоголь, лекарство") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IntakeKind.entries.forEach { k ->
                        FilterChip(
                            selected = kind == k,
                            onClick = {
                                kind = k
                                unit = when (k) {
                                    IntakeKind.WATER -> "мл"
                                    IntakeKind.COFFEE, IntakeKind.TEA -> "чаш."
                                    IntakeKind.ALCOHOL -> "мл"
                                    IntakeKind.MEAL -> "г"
                                    IntakeKind.SMOKING -> "шт."
                                    else -> "табл."
                                }
                            },
                            label = { Text(k.titleRu, maxLines = 1) }
                        )
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Название (напр. Ибупрофен, Вино, Овсянка)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = amount,
                        onValueChange = { amount = it },
                        label = { Text("Кол-во") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Ед.") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Комментарий / реакция ЖКТ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val finalName = name.trim().ifBlank { kind.titleRu }
                val num = amount.replace(',', '.').toDoubleOrNull()
                onSave(kind, finalName, num, unit.trim(), notes.trim())
            }) { Text("Добавить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun EditDocumentDialog(
    doc: MedicalDocument,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit
) {
    var title by remember { mutableStateOf(doc.title) }
    var clinic by remember { mutableStateOf(doc.doctorOrClinic) }
    var summary by remember { mutableStateOf(doc.extractedSummary) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Исправить данные документа") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = clinic, onValueChange = { clinic = it }, label = { Text("Врач / лаборатория") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = summary, onValueChange = { summary = it }, label = { Text("Выжимка / показатели") }, modifier = Modifier.fillMaxWidth().height(120.dp))
            }
        },
        confirmButton = { Button(onClick = { onSave(title, clinic, summary) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private fun tabIconVector(tab: MainTab) = when (tab) {
    MainTab.TODAY -> AppIcons.HeartPulse
    MainTab.HISTORY -> AppIcons.Timeline
    MainTab.ANALYTICS -> AppIcons.Chart
    MainTab.DOCS -> AppIcons.Document
    MainTab.PROFILE -> AppIcons.Person
}
