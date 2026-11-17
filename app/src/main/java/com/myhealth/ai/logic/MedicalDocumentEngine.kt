package com.myhealth.ai.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.myhealth.ai.model.AiSettings
import com.myhealth.ai.model.DataSourceTrust
import com.myhealth.ai.model.DocType
import com.myhealth.ai.model.LabFlag
import com.myhealth.ai.model.LabMarker
import com.myhealth.ai.model.MedicalDocument
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class ImportedMedicalDocResult(
    val document: MedicalDocument,
    val extractedMarkers: List<LabMarker>,
    val statusMessage: String
)

/**
 * Imports PDFs/images without touching originals, runs multi-page ML Kit OCR,
 * extracts common German/Russian lab markers offline, and optionally enhances via Gemini Vision.
 */
object MedicalDocumentEngine {

    private fun attachmentsDir(context: Context): File =
        File(context.filesDir, "attachments").apply { mkdirs() }

    fun createTempCameraFile(context: Context, prefix: String = "medcam"): File {
        val dir = File(context.cacheDir, "scans").apply { mkdirs() }
        return File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
    }

    fun copyUriToAttachments(
        context: Context,
        sourceUri: Uri,
        forcedMime: String? = null,
        forcedName: String? = null
    ): Triple<File, String, String> {
        val mime = forcedMime
            ?: context.contentResolver.getType(sourceUri)
            ?: if (sourceUri.toString().lowercase().endsWith(".pdf")) "application/pdf" else "image/jpeg"
        val origName = forcedName ?: resolveDisplayName(
            context,
            sourceUri,
            if (mime == "application/pdf") "medical_${System.currentTimeMillis()}.pdf" else "scan_${System.currentTimeMillis()}.jpg"
        )
        val ext = if (mime == "application/pdf") "pdf" else "jpg"
        val destFile = File(attachmentsDir(context), "${UUID.randomUUID()}.$ext")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(destFile).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Не удалось прочитать выбранный файл")
        return Triple(destFile, mime, origName)
    }

    fun processStoredDocument(
        storedFile: File,
        mime: String,
        displayName: String,
        docType: DocType,
        profileId: String,
        settings: AiSettings,
        nowMillis: Long = System.currentTimeMillis()
    ): ImportedMedicalDocResult {
        val bitmaps = if (mime == "application/pdf") {
            renderPdfPages(storedFile, maxPages = 5)
        } else {
            listOfNotNull(decodeScaledBitmap(storedFile, maxDim = 1800))
        }

        val ocrText = buildString {
            bitmaps.forEachIndexed { idx, bmp ->
                val pageText = runMlKitOcr(bmp)
                if (pageText.isNotBlank()) {
                    if (isNotEmpty()) append("\n--- Стр. ${idx + 1} ---\n")
                    append(pageText)
                }
            }
        }.trim()

        val docId = UUID.randomUUID().toString()
        val firstJpegBytes = bitmaps.firstOrNull()?.let { bmp ->
            ByteArrayOutputStream().use { bos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 82, bos)
                bos.toByteArray()
            }
        }

        val aiResult = GeminiHealthClient.analyzeMedicalDocumentWithVision(
            imageBytes = firstJpegBytes,
            ocrText = ocrText,
            documentId = docId,
            profileId = profileId,
            measuredAtMillis = nowMillis,
            settings = settings
        )

        val offlineMarkers = extractCommonLabMarkersOffline(ocrText, docId, profileId, nowMillis)
        val finalMarkers = if (!aiResult?.markers.isNullOrEmpty()) aiResult!!.markers else offlineMarkers

        val finalTitle = aiResult?.title?.takeIf { it.isNotBlank() }
            ?: inferDocumentTitle(ocrText, displayName, docType)
        val finalSummary = aiResult?.summary?.takeIf { it.isNotBlank() }
            ?: buildOfflineDocSummary(ocrText, finalMarkers, docType)

        val trust = when (docType) {
            DocType.EYE_OR_SKIN_PHOTO -> DataSourceTrust.USER_INPUT
            DocType.DEVICE_PHOTO -> DataSourceTrust.VALIDATED_DEVICE
            else -> DataSourceTrust.CLINICIAN_OR_LAB
        }

        val doc = MedicalDocument(
            id = docId,
            profileId = profileId,
            documentDateMillis = nowMillis,
            createdAtMillis = nowMillis,
            docType = docType,
            title = finalTitle,
            doctorOrClinic = aiResult?.doctorOrClinic.orEmpty(),
            originalFilePath = storedFile.absolutePath,
            mimeType = mime,
            ocrText = ocrText,
            extractedSummary = finalSummary,
            userCorrected = false,
            sourceTrust = trust
        )

        val status = when {
            aiResult != null -> "Документ сохранён; разобрано через Gemini (${finalMarkers.size} показателей)"
            ocrText.isNotBlank() -> "Документ сохранён; локальный OCR извлёк ${finalMarkers.size} показателей"
            else -> "Файл сохранён в архиве без распознанного текста"
        }
        return ImportedMedicalDocResult(doc, finalMarkers, status)
    }

    /**
     * Parses frequent lab markers from Russian/German/English OCR text lines.
     */
    fun extractCommonLabMarkersOffline(
        ocrText: String,
        documentId: String,
        profileId: String,
        measuredAtMillis: Long
    ): List<LabMarker> {
        if (ocrText.isBlank()) return emptyList()
        val specs = listOf(
            MarkerSpec("Глюкоза / Glucose", Regex("""\b(?:glukose|glucose|глюкоз[аы])\b[^0-9\n]{0,20}(\d{2,3}(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE), "мг/дл", 70.0, 100.0),
            MarkerSpec("HbA1c", Regex("""\bhba1c\b[^0-9\n]{0,20}(\d{1,2}(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE), "%", 4.0, 5.7),
            MarkerSpec("TSH / ТТГ", Regex("""\b(?:tsh|ттг)\b[^0-9\n]{0,20}(\d{1,2}(?:[.,]\d{1,3})?)""", RegexOption.IGNORE_CASE), "мМЕ/л", 0.27, 4.2),
            MarkerSpec("CRP / СРБ", Regex("""\b(?:crp|c-reakt\w*|срб)\b[^0-9\n]{0,20}(\d{1,3}(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE), "мг/л", 0.0, 5.0),
            MarkerSpec("Холестерин общ.", Regex("""\b(?:cholesterin|cholesterol|холестерин\s*(?:общ\w*)?)\b[^0-9\n]{0,20}(\d{1,3}(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE), "мг/дл", 0.0, 200.0),
            MarkerSpec("LDL / ЛПНП", Regex("""\b(?:ldl|лпнп)\b[^0-9\n]{0,20}(\d{1,3}(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE), "мг/дл", 0.0, 116.0),
            MarkerSpec("HDL / ЛПВП", Regex("""\b(?:hdl|лпвп)\b[^0-9\n]{0,20}(\d{1,3}(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE), "мг/дл", 40.0, 120.0),
            MarkerSpec("Триглицериды", Regex("""\b(?:triglycerid\w*|триглицерид\w*)\b[^0-9\n]{0,20}(\d{1,3}(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE), "мг/дл", 0.0, 150.0),
            MarkerSpec("Гемоглобин (Hb)", Regex("""\b(?:hämoglobin|hemoglobin|гемоглобин)\b[^0-9\n]{0,20}(\d{1,3}(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE), "г/дл", 12.0, 17.5),
            MarkerSpec("Ферритин", Regex("""\b(?:ferritin|ферритин)\b[^0-9\n]{0,20}(\d{1,4}(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE), "нг/мл", 20.0, 300.0),
            MarkerSpec("Витамин D (25-OH)", Regex("""\b(?:vitamin\s*d|25-oh|витамин\s*d)\b[^0-9\n]{0,20}(\d{1,3}(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE), "нг/мл", 30.0, 100.0),
            MarkerSpec("Креатинин", Regex("""\b(?:kreatinin|creatinine|креатинин)\b[^0-9\n]{0,20}(\d{1,3}(?:[.,]\d{1,2})?)""", RegexOption.IGNORE_CASE), "мг/дл", 0.6, 1.3)
        )

        val found = mutableListOf<LabMarker>()
        for (spec in specs) {
            val m = spec.regex.find(ocrText) ?: continue
            val valNum = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: continue
            val flag = when {
                valNum < spec.refMin -> LabFlag.LOW
                valNum > spec.refMax -> LabFlag.HIGH
                else -> LabFlag.NORMAL
            }
            found.add(
                LabMarker(
                    id = UUID.randomUUID().toString(),
                    documentId = documentId,
                    profileId = profileId,
                    measuredAtMillis = measuredAtMillis,
                    markerName = spec.label,
                    value = valNum,
                    unit = spec.unit,
                    refMin = spec.refMin,
                    refMax = spec.refMax,
                    flag = flag,
                    userVerified = false
                )
            )
        }
        return found
    }

    private fun inferDocumentTitle(ocrText: String, fallbackName: String, docType: DocType): String {
        val firstLine = ocrText.lineSequence().map { it.trim() }.firstOrNull { it.length in 5..60 }
        return firstLine ?: "${docType.titleRu}: $fallbackName"
    }

    private fun buildOfflineDocSummary(ocrText: String, markers: List<LabMarker>, docType: DocType): String {
        if (docType == DocType.EYE_OR_SKIN_PHOTO) {
            return "Фото для визуального наблюдения и консультации профильного врача (не заменяет очный осмотр)."
        }
        if (markers.isNotEmpty()) {
            return "Извлечено показателей: ${markers.joinToString(", ") { "${it.markerName} ${it.value} ${it.unit}" }}"
        }
        return ocrText.take(260).ifBlank { "Оригинал сохранён без распознанного текста." }
    }

    private fun resolveDisplayName(context: Context, uri: Uri, fallback: String): String {
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        }
        return fallback
    }

    private fun renderPdfPages(pdfFile: File, maxPages: Int): List<Bitmap> = runCatching {
        val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use {
            PdfRenderer(it).use { renderer ->
                val count = renderer.pageCount.coerceAtMost(maxPages)
                val out = mutableListOf<Bitmap>()
                for (i in 0 until count) {
                    renderer.openPage(i).use { page ->
                        val scale = 2
                        val w = (page.width * scale).coerceAtMost(2000)
                        val h = (page.height * scale).coerceAtMost(2800)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        out.add(bmp)
                    }
                }
                out
            }
        }
    }.getOrDefault(emptyList())

    fun extractImageOcrAndJpeg(imageFile: File): Pair<String, ByteArray?> {
        val bmp = decodeScaledBitmap(imageFile, maxDim = 1800) ?: return "" to null
        val ocr = runMlKitOcr(bmp)
        val jpeg = ByteArrayOutputStream().use { bos ->
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
            bos.toByteArray()
        }
        return ocr to jpeg
    }

    private fun decodeScaledBitmap(file: File, maxDim: Int): Bitmap? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
        val dec = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeFile(file.absolutePath, dec)
    }.getOrNull()

    private fun runMlKitOcr(bitmap: Bitmap): String = runCatching {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        Tasks.await(recognizer.process(image)).text.trim()
    }.getOrDefault("")

    private data class MarkerSpec(
        val label: String,
        val regex: Regex,
        val unit: String,
        val refMin: Double,
        val refMax: Double
    )
}
