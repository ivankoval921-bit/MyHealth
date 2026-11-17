package com.myhealth.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.telephony.SmsManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.myhealth.ai.logic.AppMeta
import com.myhealth.ai.logic.DictationState
import com.myhealth.ai.logic.DictationTarget
import com.myhealth.ai.logic.MedicalDocumentEngine
import com.myhealth.ai.logic.PocketOrientationGuard
import com.myhealth.ai.logic.UpdateChecker
import com.myhealth.ai.model.DocType
import com.myhealth.ai.model.MedicalDocument
import com.myhealth.ai.model.ReportLanguage
import com.myhealth.ai.ui.MainViewModel
import com.myhealth.ai.ui.MyHealthApp
import com.myhealth.ai.ui.UiEffect
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {

    private enum class CameraTarget { DOC, MEAL, DEVICE }

    private val viewModel: MainViewModel by viewModels()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var pocketGuard: PocketOrientationGuard? = null
    private var pendingVoiceRestart: Runnable? = null
    private var consecutiveVoiceErrors: Int = 0
    private var tts: TextToSpeech? = null
    private var ttsReady: Boolean = false
    private var activeRingtone: Ringtone? = null
    private var pendingCameraFile: File? = null
    private var pendingDocType: DocType = DocType.LAB_RESULT
    private var pendingCameraTarget: CameraTarget = CameraTarget.DOC
    private var pendingUpdateApkFile: File? = null

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        syncPermissionsState()
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val file = pendingCameraFile
        pendingCameraFile = null
        if (ok && file != null && file.exists()) {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            when (pendingCameraTarget) {
                CameraTarget.MEAL -> viewModel.importMealPhoto(uri)
                CameraTarget.DEVICE -> viewModel.importDevicePhoto(uri)
                CameraTarget.DOC -> viewModel.importMedicalFile(uri, pendingDocType, forcedMime = "image/jpeg")
            }
        }
    }

    private val mealGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importMealPhoto(uri)
    }

    private val deviceGalleryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importDevicePhoto(uri)
    }

    private val docPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.importMedicalFile(uri, pendingDocType)
    }

    private val backupRestoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.restoreBackupZip(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncPermissionsState()
        handleIncomingIntent(intent)
        if (viewModel.aiSettings.value.autoWeatherEnabled) {
            refreshWeatherBestLocation()
        }

        tts = TextToSpeech(this) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
        }

        lifecycleScope.launch {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is UiEffect.SetCheckInAlarm -> setCheckInAlarm(effect.active)
                    is UiEffect.SendAutoEmergencySms -> sendAutoEmergencySms(effect.phone, effect.message)
                    is UiEffect.InstallUpdateApk -> installUpdateApk(effect.file)
                }
            }
        }

        setContent {
            MaterialTheme {
                MyHealthApp(
                    viewModel = viewModel,
                    onStartOrToggleVoice = ::startOrToggleVoiceDictation,
                    onPauseVoice = ::pauseVoiceDictation,
                    onResumeVoice = ::resumeVoiceDictation,
                    onStopVoice = { stopVoiceDictation(null) },
                    onRequestPermissions = ::requestAppPermissions,
                    onRequestSmsPermission = ::requestSmsPermission,
                    onRefreshWeatherWithGps = ::refreshWeatherBestLocation,
                    onPickMealPhotoCamera = { launchCameraCapture(CameraTarget.MEAL, DocType.DEVICE_PHOTO) },
                    onPickMealPhotoGallery = { mealGalleryLauncher.launch(arrayOf("image/*")) },
                    onPickDevicePhotoCamera = { launchCameraCapture(CameraTarget.DEVICE, DocType.DEVICE_PHOTO) },
                    onPickDevicePhotoGallery = { deviceGalleryLauncher.launch(arrayOf("image/*")) },
                    onImportDocCamera = { dt -> launchCameraCapture(CameraTarget.DOC, dt) },
                    onImportDocFile = { dt ->
                        pendingDocType = dt
                        docPickerLauncher.launch(arrayOf("application/pdf", "image/*"))
                    },
                    onOpenDocumentFile = ::openStoredDocument,
                    onGenerateDoctorPdf = ::generateAndSharePdf,
                    onExportBackupZip = {
                        viewModel.exportBackupZip { zipFile -> shareFile(zipFile, "application/zip", "Резервная копия Моё здоровье") }
                    },
                    onRestoreBackupZip = { backupRestoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    onDialEmergency = { number ->
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                    },
                    onNotifyTrustedContact = { phone, msg ->
                        val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
                            putExtra("sms_body", msg)
                        }
                        runCatching { startActivity(smsIntent) }
                            .onFailure { viewModel.showBanner("Не удалось открыть SMS") }
                    },
                    onSpeakEmergencyCard = ::speakEmergencyCard,
                    onStopSpeaking = { runCatching { tts?.stop() } },
                    onOpenGeminiKeyPage = {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey")))
                    },
                    onPasteGeminiKeyFromClipboard = ::readClipboardText,
                    onOpenGitHubRepo = {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AppMeta.GITHUB_URL)))
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val pendingFile = pendingUpdateApkFile
        if (pendingFile != null && pendingFile.exists() && UpdateChecker.canRequestPackageInstalls(this)) {
            pendingUpdateApkFile = null
            installUpdateApk(pendingFile)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        if (viewModel.ui.value.isListening) {
            pauseVoiceDictation()
        }
    }

    override fun onDestroy() {
        cancelPendingVoiceRestart()
        mainHandler.removeCallbacksAndMessages(null)
        pocketGuard?.stop()
        setCheckInAlarm(false)
        runCatching { speechRecognizer?.cancel(); speechRecognizer?.destroy() }
        runCatching { tts?.stop(); tts?.shutdown() }
        super.onDestroy()
    }

    private fun syncPermissionsState() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val loc = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val sms = ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        viewModel.updatePermissions(mic, cam, loc, sms)
    }

    private fun requestAppPermissions() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 33) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(needed.toTypedArray())
    }

    private fun requestSmsPermission() {
        permissionsLauncher.launch(arrayOf(Manifest.permission.SEND_SMS))
    }

    private fun cancelPendingVoiceRestart() {
        pendingVoiceRestart?.let { mainHandler.removeCallbacks(it) }
        pendingVoiceRestart = null
    }

    private fun flushPartialSpeech() {
        val tail = viewModel.ui.value.partialSpeechText.trim()
        if (tail.isNotEmpty()) viewModel.appendDictatedText(tail)
        viewModel.setPartialSpeech("")
    }

    private fun startOrToggleVoiceDictation(target: DictationTarget) {
        val st = viewModel.ui.value
        if (st.isListening && st.dictationTarget == target) {
            stopVoiceDictation(null)
            return
        }
        if (st.isPaused && st.dictationTarget == target) {
            resumeVoiceDictation()
            return
        }
        if (!st.micGranted) {
            requestAppPermissions()
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            viewModel.showBanner("Системное распознавание речи недоступно — введите текст")
            return
        }
        cancelPendingVoiceRestart()
        flushPartialSpeech()
        consecutiveVoiceErrors = 0
        viewModel.setDictationState(DictationState.LISTENING, target)
        startPocketGuardIfEnabled()
        launchRecognizerSession()
    }

    private fun pauseVoiceDictation() {
        if (!viewModel.ui.value.isListening) return
        cancelPendingVoiceRestart()
        flushPartialSpeech()
        pocketGuard?.stop()
        runCatching { speechRecognizer?.stopListening(); speechRecognizer?.destroy() }
        speechRecognizer = null
        viewModel.setDictationState(DictationState.PAUSED)
    }

    private fun resumeVoiceDictation() {
        val st = viewModel.ui.value
        if (!st.micGranted) {
            requestAppPermissions()
            return
        }
        cancelPendingVoiceRestart()
        consecutiveVoiceErrors = 0
        viewModel.setDictationState(DictationState.LISTENING, st.dictationTarget)
        startPocketGuardIfEnabled()
        launchRecognizerSession()
    }

    private fun stopVoiceDictation(reasonBanner: String?) {
        cancelPendingVoiceRestart()
        flushPartialSpeech()
        pocketGuard?.stop()
        runCatching { speechRecognizer?.cancel(); speechRecognizer?.destroy() }
        speechRecognizer = null
        viewModel.setDictationState(DictationState.IDLE)
        if (!reasonBanner.isNullOrBlank()) {
            viewModel.showBanner(reasonBanner)
        }
    }

    private fun startPocketGuardIfEnabled() {
        pocketGuard?.stop()
        if (!viewModel.aiSettings.value.autoStopMicInPocket) return
        if (pocketGuard == null) {
            pocketGuard = PocketOrientationGuard(this) { reason ->
                mainHandler.post {
                    if (viewModel.ui.value.isListening) {
                        stopVoiceDictation(reason.messageRu)
                    }
                }
            }
        }
        pocketGuard?.start()
    }

    private fun scheduleNextRecognizerSession(delayMs: Long) {
        cancelPendingVoiceRestart()
        val runnable = Runnable {
            pendingVoiceRestart = null
            if (!isFinishing && !isDestroyed && viewModel.ui.value.isListening) {
                launchRecognizerSession()
            }
        }
        pendingVoiceRestart = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun launchRecognizerSession() {
        if (!viewModel.ui.value.isListening || isFinishing || isDestroyed) return
        runCatching { speechRecognizer?.cancel(); speechRecognizer?.destroy() }
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() {
                consecutiveVoiceErrors = 0
            }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                flushPartialSpeech()
                if (!viewModel.ui.value.isListening) return
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        scheduleNextRecognizerSession(250L)
                    }
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_CLIENT -> {
                        consecutiveVoiceErrors++
                        if (consecutiveVoiceErrors <= 4) {
                            scheduleNextRecognizerSession(480L)
                        } else {
                            stopVoiceDictation("Диктовка остановлена (код $error)")
                        }
                    }
                    else -> {
                        stopVoiceDictation("Диктовка остановлена (код $error)")
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                consecutiveVoiceErrors = 0
                val best = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                val tail = viewModel.ui.value.partialSpeechText.trim()
                val text = if (best.isNotEmpty()) best else tail
                viewModel.setPartialSpeech("")
                if (text.isNotEmpty()) viewModel.appendDictatedText(text)
                if (viewModel.ui.value.isListening) {
                    scheduleNextRecognizerSession(180L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (p.isNotEmpty()) viewModel.setPartialSpeech(p)
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val lang = viewModel.aiSettings.value.speechLangTag
        val recIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3_000L)
        }
        recognizer.startListening(recIntent)
    }

    @SuppressLint("MissingPermission")
    private fun refreshWeatherBestLocation() {
        val locGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (locGranted) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val loc = runCatching {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }.getOrNull()
            if (loc != null) {
                viewModel.refreshWeather(loc.latitude, loc.longitude, "GPS")
                return
            }
        }
        viewModel.refreshWeather()
    }

    private fun launchCameraCapture(target: CameraTarget, docType: DocType) {
        if (!viewModel.ui.value.cameraGranted) {
            requestAppPermissions()
            return
        }
        pendingCameraTarget = target
        pendingDocType = docType
        val prefix = when (target) {
            CameraTarget.MEAL -> "meal"
            CameraTarget.DEVICE -> "dev"
            CameraTarget.DOC -> "med"
        }
        val tmpFile = MedicalDocumentEngine.createTempCameraFile(this, prefix)
        pendingCameraFile = tmpFile
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", tmpFile)
        cameraLauncher.launch(uri)
    }

    @Suppress("DEPRECATION")
    private fun setCheckInAlarm(active: Boolean) {
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (!active) {
            runCatching { activeRingtone?.stop() }
            activeRingtone = null
            runCatching { vibrator?.cancel() }
            return
        }
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val ring = RingtoneManager.getRingtone(applicationContext, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ring.audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            }
            ring.play()
            activeRingtone = ring
        }
        runCatching {
            val pattern = longArrayOf(0L, 500L, 350L, 500L, 350L)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    @Suppress("DEPRECATION")
    private fun sendAutoEmergencySms(phone: String, message: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            viewModel.showBanner("Нет разрешения SEND_SMS для автоотправки")
            return
        }
        runCatching {
            val mgr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            }
            val parts = mgr.divideMessage(message)
            mgr.sendMultipartTextMessage(phone, null, parts, null, null)
            viewModel.showBanner("Экстренное авто-SMS отправлено на $phone")
        }.onFailure { ex ->
            viewModel.showBanner("Ошибка авто-SMS: ${ex.message}")
        }
    }

    private fun speakEmergencyCard(text: String, langTag: String) {
        val engine = tts
        if (engine == null || !ttsReady) {
            viewModel.showBanner("Синтез речи ещё инициализируется или недоступен")
            return
        }
        val locale = Locale.forLanguageTag(langTag)
        val res = engine.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.language = Locale.US
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "myhealth_112_card")
    }

    private fun installUpdateApk(file: File) {
        if (!file.exists()) {
            viewModel.showBanner("Файл обновления не найден")
            return
        }
        val launched = runCatching {
            UpdateChecker.launchInstallerOrPermissionSettings(this, file)
        }.getOrElse { ex ->
            viewModel.showBanner("Ошибка запуска установщика: ${ex.message}")
            false
        }
        if (!launched) {
            pendingUpdateApkFile = file
            viewModel.showBanner("Разрешите установку из этого источника — затем вернитесь в приложение")
        }
    }

    private fun openStoredDocument(doc: MedicalDocument) {
        val file = File(doc.originalFilePath)
        if (!file.exists()) {
            viewModel.showBanner("Файл не найден на устройстве")
            return
        }
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, doc.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(viewIntent) }
            .onFailure { viewModel.showBanner("Нет приложения для открытия ${doc.mimeType}") }
    }

    private fun generateAndSharePdf(daysBack: Int, lang: ReportLanguage) {
        viewModel.buildDoctorPdf(daysBack, lang) { file ->
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(intent, "Открыть или отправить выписку PDF")
            runCatching { startActivity(chooser) }
                .onFailure { shareFile(file, "application/pdf", "Медицинская выписка PDF") }
        }
    }

    private fun shareFile(file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, title))
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("extra_check_update", false) == true) {
            viewModel.checkForAppUpdate(silent = false)
        }
        if (intent?.action != Intent.ACTION_SEND) return
        val mime = intent.type.orEmpty()
        if (mime == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
            if (sharedText.isNotEmpty()) viewModel.setInputText(sharedText)
        } else {
            @Suppress("DEPRECATION")
            val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (streamUri != null) {
                viewModel.importMedicalFile(streamUri, DocType.EPA_EXPORT, forcedMime = mime.ifBlank { null })
            }
        }
    }

    private fun readClipboardText(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
        return text?.takeIf { it.isNotEmpty() }
    }
}
