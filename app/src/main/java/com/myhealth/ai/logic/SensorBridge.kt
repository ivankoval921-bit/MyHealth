package com.myhealth.ai.logic

import com.myhealth.ai.model.VitalMeasurement

/**
 * Open adapter contract for Stage 2 external health hardware:
 * - Android Health Connect (watch sleep, HR, HRV, SpO2, steps, recorded ECG/BP)
 * - Direct Bluetooth LE GATT medical devices (e.g., BLE blood pressure cuffs, pulse oximeters, chest HR straps).
 */
interface SensorAdapter {
    val adapterName: String
    fun supportsOnDemandTrigger(): Boolean
    fun syncRecentReadings(profileId: String, sinceMillis: Long): List<VitalMeasurement>
}

object SensorCapabilityInfo {
    const val WATCH_ON_DEMAND_NOTE =
        "Умные часы (Samsung/Garmin/Apple/Amazfit) требуют прикосновения пальца на корпусе для ЭКГ и манжеты для АД, поэтому удалённый автозапуск с телефона невозможен; " +
            "через Android Health Connect и стандартные BLE-приборы данные подтягиваются после замера (Этап 2)."
}
