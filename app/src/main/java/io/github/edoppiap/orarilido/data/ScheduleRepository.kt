package io.github.edoppiap.orarilido.data

import android.content.Context
import io.github.edoppiap.orarilido.schedule.ScheduleParser
import io.github.edoppiap.orarilido.schedule.ScheduleProvider
import io.github.edoppiap.orarilido.schedule.TimetableScheduleProvider

/**
 * Da dove arriva l'orario. Per ora solo dalla copia inclusa nell'app
 * (src/main/assets/schedule.json); nella fase 6 si aggiungerà la copia
 * scaricata da internet, con questa come ultima riserva.
 */
class ScheduleRepository(private val context: Context) {

    fun load(): ScheduleProvider {
        val text = context.assets.open("schedule.json").bufferedReader().use { it.readText() }
        return TimetableScheduleProvider(ScheduleParser.parse(text))
    }
}
