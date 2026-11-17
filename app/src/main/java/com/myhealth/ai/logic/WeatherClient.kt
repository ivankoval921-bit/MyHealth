package com.myhealth.ai.logic

import com.myhealth.ai.model.WeatherSnapshot
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Fetches free atmospheric pressure, temperature, humidity, pressure deltas (6h & 24h),
 * and European AQI/PM2.5 from Open-Meteo (no API key required).
 */
object WeatherClient {

    private const val HPA_TO_MMHG = 0.750062

    fun fetchCurrentWeather(
        latitude: Double,
        longitude: Double,
        locationName: String = "Текущая локация",
        includeGeomagneticKp: Boolean = false
    ): WeatherSnapshot {
        val latStr = String.format(Locale.US, "%.4f", latitude)
        val lonStr = String.format(Locale.US, "%.4f", longitude)

        val weatherUrl =
            "https://api.open-meteo.com/v1/forecast?latitude=$latStr&longitude=$lonStr" +
                "&current=temperature_2m,relative_humidity_2m,pressure_msl" +
                "&hourly=pressure_msl&past_days=2&forecast_days=1&timezone=auto"
        val weatherJsonStr = getUrl(weatherUrl)

        var aqi: Int? = null
        var pm25: Double? = null
        runCatching {
            val aqUrl =
                "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$latStr&longitude=$lonStr" +
                    "&current=european_aqi,pm2_5"
            val aqJson = JSONObject(getUrl(aqUrl)).optJSONObject("current")
            if (aqJson != null) {
                if (aqJson.has("european_aqi") && !aqJson.isNull("european_aqi")) {
                    aqi = aqJson.optInt("european_aqi")
                }
                if (aqJson.has("pm2_5") && !aqJson.isNull("pm2_5")) {
                    pm25 = aqJson.optDouble("pm2_5")
                }
            }
        }

        var kp: Double? = null
        if (includeGeomagneticKp) {
            kp = runCatching { fetchLatestNoaaKpIndex() }.getOrNull()
        }

        return parseOpenMeteoJson(
            weatherJsonStr = weatherJsonStr,
            latitude = latitude,
            longitude = longitude,
            locationName = locationName,
            aqiEuropean = aqi,
            pm25 = pm25,
            kpIndex = kp
        )
    }

    fun parseOpenMeteoJson(
        weatherJsonStr: String,
        latitude: Double,
        longitude: Double,
        locationName: String,
        aqiEuropean: Int? = null,
        pm25: Double? = null,
        kpIndex: Double? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): WeatherSnapshot {
        val root = JSONObject(weatherJsonStr)
        val current = root.getJSONObject("current")
        val tempC = current.optDouble("temperature_2m", 0.0)
        val humidity = current.optInt("relative_humidity_2m", 0)
        val currentTime = current.optString("time", "")
        val currentPressure = current.optDouble("pressure_msl", 1013.2)

        val hourly = root.optJSONObject("hourly")
        val times = hourly?.optJSONArray("time")
        val pressures = hourly?.optJSONArray("pressure_msl")

        var delta6h = 0.0
        var delta24h = 0.0
        if (times != null && pressures != null && times.length() == pressures.length()) {
            val curHourPrefix = if (currentTime.length >= 13) currentTime.substring(0, 13) else ""
            var curIdx = -1
            for (i in 0 until times.length()) {
                if (curHourPrefix.isNotEmpty() && times.optString(i).startsWith(curHourPrefix)) {
                    curIdx = i
                    break
                }
            }
            if (curIdx < 0) curIdx = (times.length() - 25).coerceAtLeast(0)

            val pNow = pressures.optDouble(curIdx, currentPressure)
            val idx6 = (curIdx - 6).coerceAtLeast(0)
            val idx24 = (curIdx - 24).coerceAtLeast(0)
            val p6 = pressures.optDouble(idx6, pNow)
            val p24 = pressures.optDouble(idx24, pNow)
            delta6h = ((pNow - p6) * 10.0).roundToInt() / 10.0
            delta24h = ((pNow - p24) * 10.0).roundToInt() / 10.0
        }

        val mmHg = ((currentPressure * HPA_TO_MMHG) * 10.0).roundToInt() / 10.0
        val arrow24 = when {
            delta24h <= -4.0 -> "резко падает (${formatSigned(delta24h)} гПа/24ч)"
            delta24h >= 4.0 -> "растёт (+${formatSigned(delta24h).trimStart('+')} гПа/24ч)"
            else -> "стабильно (${formatSigned(delta24h)} гПа/24ч)"
        }
        val aqiStr = aqiEuropean?.let { " · AQI $it" } ?: ""
        val kpStr = kpIndex?.let { " · Kp ${String.format(Locale.US, "%.1f", it)}" } ?: ""
        val summary = "${String.format(Locale.US, "%.1f", tempC)}°C · $humidity% · " +
            "${mmHg.toInt()} мм рт.ст. ($arrow24)$aqiStr$kpStr"

        return WeatherSnapshot(
            id = UUID.randomUUID().toString(),
            timestampMillis = nowMillis,
            latitude = latitude,
            longitude = longitude,
            locationName = locationName,
            temperatureC = tempC,
            humidityPct = humidity,
            pressureHpa = currentPressure,
            pressureMmHg = mmHg,
            pressureDelta6hHpa = delta6h,
            pressureDelta24hHpa = delta24h,
            aqiEuropean = aqiEuropean,
            pm25 = pm25,
            kpIndex = kpIndex,
            summaryText = summary
        )
    }

    private fun fetchLatestNoaaKpIndex(): Double? {
        val raw = getUrl("https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json")
        val arr = org.json.JSONArray(raw)
        if (arr.length() < 2) return null
        val lastRow = arr.optJSONArray(arr.length() - 1) ?: return null
        return lastRow.optString(1).toDoubleOrNull()
    }

    private fun getUrl(urlStr: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 12_000
            setRequestProperty("User-Agent", "MyHealthAndroid/1.0")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.readText().orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun formatSigned(v: Double): String =
        if (v >= 0) "+${String.format(Locale.US, "%.1f", v)}" else String.format(Locale.US, "%.1f", v)
}
