package com.myhealth.ai.data

import android.content.Context
import android.content.SharedPreferences
import com.myhealth.ai.model.AiSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HealthPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("myhealth_prefs", Context.MODE_PRIVATE)

    private val _aiSettings = MutableStateFlow(loadAiSettings())
    val aiSettings: StateFlow<AiSettings> = _aiSettings.asStateFlow()

    var activeProfileId: String
        get() = prefs.getString("active_profile_id", "profile_primary").orEmpty().ifBlank { "profile_primary" }
        set(value) = prefs.edit().putString("active_profile_id", value).apply()

    var lastLat: Double
        get() = prefs.getFloat("last_lat", 51.2562f).toDouble()
        set(value) = prefs.edit().putFloat("last_lat", value.toFloat()).apply()

    var lastLon: Double
        get() = prefs.getFloat("last_lon", 7.1508f).toDouble()
        set(value) = prefs.edit().putFloat("last_lon", value.toFloat()).apply()

    var lastLocationName: String
        get() = prefs.getString("last_loc_name", "Текущая локация").orEmpty().ifBlank { "Текущая локация" }
        set(value) = prefs.edit().putString("last_loc_name", value).apply()

    fun updateAiSettings(settings: AiSettings) {
        val safeModel = sanitizeModel(settings.geminiModel)
        val clean = settings.copy(geminiModel = safeModel)
        _aiSettings.value = clean
        prefs.edit()
            .putBoolean("ai_consent_granted", clean.aiConsentGranted)
            .putString("gemini_api_key", clean.geminiApiKey.trim())
            .putString("gemini_model", safeModel)
            .putString("speech_lang_tag", clean.speechLangTag)
            .putBoolean("auto_weather_enabled", clean.autoWeatherEnabled)
            .putBoolean("include_geomagnetic_ref", clean.includeGeomagneticRef)
            .apply()
    }

    private fun loadAiSettings(): AiSettings {
        val model = sanitizeModel(prefs.getString("gemini_model", "gemini-3.8-flash").orEmpty())
        return AiSettings(
            aiConsentGranted = prefs.getBoolean("ai_consent_granted", false),
            geminiApiKey = prefs.getString("gemini_api_key", "").orEmpty(),
            geminiModel = model,
            speechLangTag = prefs.getString("speech_lang_tag", "ru-RU").orEmpty().ifBlank { "ru-RU" },
            autoWeatherEnabled = prefs.getBoolean("auto_weather_enabled", true),
            includeGeomagneticRef = prefs.getBoolean("include_geomagnetic_ref", false)
        )
    }

    companion object {
        val SUPPORTED_GEMINI_MODELS = listOf(
            "gemini-3.8-flash",
            "gemini-3.5-flash-lite",
            "gemini-4-argon"
        )

        fun sanitizeModel(raw: String): String {
            val trimmed = raw.trim()
            return if (trimmed in SUPPORTED_GEMINI_MODELS) trimmed else "gemini-3.8-flash"
        }
    }
}
