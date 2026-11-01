package dev.dagent.core.places

import java.time.DayOfWeek
import java.time.LocalDateTime

data class OpenState(val open: Boolean, val detail: String)

/**
 * A practical subset of the OpenStreetMap `opening_hours` syntax: `24/7`,
 * weekday lists and ranges (`Mo-Fr`, `Sa,Su`), several time ranges per day,
 * ranges past midnight (`18:00-02:00`) and `off`/`closed`. Later rules override
 * earlier ones for the same day, as in OSM. Rules it cannot read (months,
 * holidays, sunrise) make [parse] return null so callers show the raw text.
 */
class OpeningHours private constructor(private val week: Map<DayOfWeek, List<IntRange>>, private val always: Boolean) {

    fun stateAt(now: LocalDateTime): OpenState {
        if (always) return OpenState(true, "open 24 hours")
        val day = now.dayOfWeek
        val minute = now.hour * 60 + now.minute
        val today = week[day].orEmpty()
        val yesterday = week[day.minus(1)].orEmpty()
        today.firstOrNull { minute in it }?.let { return OpenState(true, "open now, closes ${clock(it.last + 1)}") }
        yesterday.firstOrNull { it.last >= DAY && minute + DAY in it }?.let { return OpenState(true, "open now, closes ${clock(it.last + 1 - DAY)}") }
        today.filter { it.first > minute }.minByOrNull { it.first }?.let { return OpenState(false, "closed now, opens ${clock(it.first)} today") }
        for (ahead in 1..7) {
            val d = day.plus(ahead.toLong())
            val first = week[d].orEmpty().minByOrNull { it.first } ?: continue
            val whenText = if (ahead == 1) "tomorrow" else d.name.lowercase().replaceFirstChar { it.uppercase() }
            return OpenState(false, "closed now, opens ${clock(first.first)} $whenText")
        }
        return OpenState(false, "closed")
    }

    companion object {
        private const val DAY = 24 * 60
        private val DAYS = mapOf(
            "mo" to DayOfWeek.MONDAY, "tu" to DayOfWeek.TUESDAY, "we" to DayOfWeek.WEDNESDAY, "th" to DayOfWeek.THURSDAY,
            "fr" to DayOfWeek.FRIDAY, "sa" to DayOfWeek.SATURDAY, "su" to DayOfWeek.SUNDAY
        )
        private val TIME_RANGE = Regex("""^(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})\+?$""")
        private val DAY_TOKEN = Regex("""^(mo|tu|we|th|fr|sa|su)(\s*-\s*(mo|tu|we|th|fr|sa|su))?$""")

        fun parse(raw: String?): OpeningHours? {
            val text = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            if (text == "24/7" || text == "00:00-24:00" || text == "mo-su 00:00-24:00") return OpeningHours(emptyMap(), true)
            val week = HashMap<DayOfWeek, List<IntRange>>()
            val rules = text.split(';').flatMap { splitCommaRules(it.trim()) }.filter { it.isNotEmpty() }
            if (rules.isEmpty()) return null
            for (rule in rules) {
                val (days, times) = splitRule(rule) ?: return null
                val ranges = when (times) {
                    "off", "closed" -> emptyList()
                    else -> times.split(',').map { parseRange(it.trim()) ?: return null }
                }
                days.forEach { week[it] = ranges }
            }
            return OpeningHours(week, false)
        }

        /** "Mo-Fr 09:00-17:00, Sa 10:00-14:00" uses a comma before a new weekday group. */
        private fun splitCommaRules(rule: String): List<String> {
            val out = mutableListOf<String>()
            val parts = rule.split(',')
            val current = StringBuilder()
            for (p in parts) {
                val t = p.trim()
                val startsWithDay = t.length >= 2 && t.substring(0, 2) in DAYS && current.isNotEmpty() && current.any { it.isDigit() }
                if (startsWithDay) {
                    out += current.toString()
                    current.clear()
                    current.append(t)
                } else {
                    if (current.isNotEmpty()) current.append(',')
                    current.append(t)
                }
            }
            if (current.isNotEmpty()) out += current.toString()
            return out
        }

        private fun splitRule(rule: String): Pair<Set<DayOfWeek>, String>? {
            val firstDigit = rule.indexOfFirst { it.isDigit() }
            val offIdx = listOf(rule.indexOf("off"), rule.indexOf("closed")).filter { it >= 0 }.minOrNull() ?: -1
            val split = listOf(firstDigit, offIdx).filter { it >= 0 }.minOrNull() ?: return null
            val dayPart = rule.substring(0, split).trim().trimEnd(':').trim()
            val timePart = rule.substring(split).trim()
            val days = if (dayPart.isEmpty()) DayOfWeek.entries.toSet() else parseDays(dayPart) ?: return null
            return days to timePart
        }

        private fun parseDays(text: String): Set<DayOfWeek>? {
            val out = LinkedHashSet<DayOfWeek>()
            for (token in text.split(',').map { it.trim() }) {
                val m = DAY_TOKEN.find(token) ?: return null
                val start = DAYS.getValue(m.groupValues[1])
                val end = m.groupValues[3].takeIf { it.isNotEmpty() }?.let { DAYS.getValue(it) } ?: start
                var d = start
                while (true) {
                    out += d
                    if (d == end) break
                    d = d.plus(1)
                }
            }
            return out
        }

        private fun parseRange(text: String): IntRange? {
            val m = TIME_RANGE.find(text) ?: return null
            val start = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            var end = m.groupValues[3].toInt() * 60 + m.groupValues[4].toInt()
            if (end <= start) end += DAY
            return start until end
        }

        private fun clock(minute: Int): String {
            val m = ((minute % DAY) + DAY) % DAY
            val h = m / 60
            val mm = m % 60
            val suffix = if (h < 12) "am" else "pm"
            val h12 = if (h % 12 == 0) 12 else h % 12
            return if (mm == 0) "$h12$suffix" else "$h12:${mm.toString().padStart(2, '0')}$suffix"
        }
    }
}
