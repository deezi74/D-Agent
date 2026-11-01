package dev.dagent.core.places

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(val lat: Double, val lon: Double, val label: String = "")

data class Place(
    val name: String,
    val address: String,
    val point: GeoPoint,
    val category: String = "",
    val openingHours: String? = null,
    /** Google's live answer when available; otherwise derived from [openingHours]. */
    val openNow: Boolean? = null,
    val hoursText: List<String> = emptyList(),
    val phone: String? = null,
    val website: String? = null,
    val rating: Double? = null,
    val ratingCount: Int? = null
)

data class Route(val meters: Double, val seconds: Double)

/**
 * Free map data by default: OpenStreetMap (Nominatim) for places and their
 * opening hours, OSRM for drive times and Open-Meteo for weather, none of which
 * need a key. A Google Places key, when set, gives better coverage and live
 * open/closed status.
 */
class PlacesClient(
    private val googleKey: () -> String? = { null },
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val userAgent: String = "D-Agent/1.0 (Android personal assistant)",
    private val nominatimBase: String = "https://nominatim.openstreetmap.org",
    private val osrmBase: String = "https://router.project-osrm.org",
    private val meteoBase: String = "https://api.open-meteo.com"
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var lastNominatimCall = 0L

    suspend fun geocode(query: String): GeoPoint? = withContext(Dispatchers.IO) {
        val url = "$nominatimBase/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "1")
            .build()
        val arr = getJson(url.toString(), nominatim = true) as? JsonArray ?: return@withContext null
        val o = arr.firstOrNull() as? JsonObject ?: return@withContext null
        GeoPoint(o.d("lat") ?: return@withContext null, o.d("lon") ?: return@withContext null, o.s("display_name").orEmpty())
    }

    suspend fun reverse(point: GeoPoint): String? = withContext(Dispatchers.IO) {
        val url = "$nominatimBase/reverse".toHttpUrl().newBuilder()
            .addQueryParameter("lat", point.lat.toString())
            .addQueryParameter("lon", point.lon.toString())
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("zoom", "16")
            .build()
        (getJson(url.toString(), nominatim = true) as? JsonObject)?.s("display_name")
    }

    suspend fun search(query: String, near: GeoPoint, limit: Int = 6): List<Place> = withContext(Dispatchers.IO) {
        val key = googleKey()
        val places = if (!key.isNullOrBlank()) googleSearch(key, query, near, limit) else osmSearch(query, near, limit)
        places.sortedBy { distanceMeters(near, it.point) }.take(limit)
    }

    private fun osmSearch(query: String, near: GeoPoint, limit: Int): List<Place> {
        fun attempt(span: Double, bounded: Boolean): List<Place> {
            val url = "$nominatimBase/search".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("limit", (limit * 2).toString())
                .addQueryParameter("extratags", "1")
                .addQueryParameter("viewbox", "${near.lon - span},${near.lat + span},${near.lon + span},${near.lat - span}")
                .addQueryParameter("bounded", if (bounded) "1" else "0")
                .build()
            val arr = getJson(url.toString(), nominatim = true) as? JsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val tags = o["extratags"] as? JsonObject
                val display = o.s("display_name").orEmpty()
                Place(
                    name = o.s("name")?.takeIf { it.isNotBlank() } ?: display.substringBefore(','),
                    address = display.split(',').drop(1).take(3).joinToString(",").trim(),
                    point = GeoPoint(o.d("lat") ?: return@mapNotNull null, o.d("lon") ?: return@mapNotNull null),
                    category = o.s("type").orEmpty().replace('_', ' '),
                    openingHours = tags?.s("opening_hours"),
                    phone = tags?.s("phone") ?: tags?.s("contact:phone"),
                    website = tags?.s("website") ?: tags?.s("contact:website")
                )
            }
        }
        return attempt(0.12, true).ifEmpty { attempt(0.5, true) }.ifEmpty { attempt(0.5, false) }
    }

    private fun googleSearch(key: String, query: String, near: GeoPoint, limit: Int): List<Place> {
        val body = buildJsonObject {
            put("textQuery", query)
            put("maxResultCount", limit.coerceIn(1, 20))
            putJsonObject("locationBias") {
                putJsonObject("circle") {
                    putJsonObject("center") {
                        put("latitude", near.lat)
                        put("longitude", near.lon)
                    }
                    put("radius", 15000.0)
                }
            }
        }
        val req = Request.Builder()
            .url("https://places.googleapis.com/v1/places:searchText")
            .header("X-Goog-Api-Key", key)
            .header(
                "X-Goog-FieldMask",
                "places.displayName,places.formattedAddress,places.location,places.currentOpeningHours.openNow," +
                    "places.currentOpeningHours.weekdayDescriptions,places.rating,places.userRatingCount," +
                    "places.nationalPhoneNumber,places.websiteUri,places.primaryTypeDisplayName"
            )
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.s("message") }.getOrNull()
                throw IOException("Google Places: ${msg ?: "HTTP ${resp.code}"}")
            }
            val arr = (json.parseToJsonElement(text) as? JsonObject)?.get("places") as? JsonArray ?: return emptyList()
            return arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val loc = o["location"] as? JsonObject ?: return@mapNotNull null
                val hours = o["currentOpeningHours"] as? JsonObject
                Place(
                    name = (o["displayName"] as? JsonObject)?.s("text") ?: return@mapNotNull null,
                    address = o.s("formattedAddress").orEmpty(),
                    point = GeoPoint(loc.d("latitude") ?: return@mapNotNull null, loc.d("longitude") ?: return@mapNotNull null),
                    category = (o["primaryTypeDisplayName"] as? JsonObject)?.s("text").orEmpty(),
                    openNow = (hours?.get("openNow") as? JsonPrimitive)?.booleanOrNull,
                    hoursText = (hours?.get("weekdayDescriptions") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                    phone = o.s("nationalPhoneNumber"),
                    website = o.s("websiteUri"),
                    rating = o.d("rating"),
                    ratingCount = (o["userRatingCount"] as? JsonPrimitive)?.intOrNull
                )
            }
        }
    }

    /** Driving route from OSRM, or null when the router is unreachable. */
    suspend fun drive(from: GeoPoint, to: GeoPoint): Route? = withContext(Dispatchers.IO) {
        val url = "$osrmBase/route/v1/driving/${from.lon},${from.lat};${to.lon},${to.lat}?overview=false"
        val o = runCatching { getJson(url, nominatim = false) as? JsonObject }.getOrNull() ?: return@withContext null
        val route = (o["routes"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return@withContext null
        Route(route.d("distance") ?: return@withContext null, route.d("duration") ?: return@withContext null)
    }

    suspend fun weather(point: GeoPoint, imperial: Boolean): String = withContext(Dispatchers.IO) {
        val url = "$meteoBase/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", point.lat.toString())
            .addQueryParameter("longitude", point.lon.toString())
            .addQueryParameter("current", "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,precipitation")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "2")
            .apply {
                if (imperial) {
                    addQueryParameter("temperature_unit", "fahrenheit")
                    addQueryParameter("wind_speed_unit", "mph")
                    addQueryParameter("precipitation_unit", "inch")
                }
            }
            .build()
        val o = getJson(url.toString(), nominatim = false) as? JsonObject ?: throw IOException("no weather data")
        val unit = if (imperial) "°F" else "°C"
        val wind = if (imperial) "mph" else "km/h"
        val cur = o["current"] as? JsonObject
        val daily = o["daily"] as? JsonObject
        fun dailyAt(name: String, i: Int): JsonElement? = (daily?.get(name) as? JsonArray)?.getOrNull(i)
        buildString {
            if (cur != null) {
                append("Now: ").append(describeWeather((cur["weather_code"] as? JsonPrimitive)?.intOrNull)).append(", ")
                append(cur.d("temperature_2m")?.roundToInt()).append(unit)
                append(" (feels ").append(cur.d("apparent_temperature")?.roundToInt()).append(unit).append("), wind ")
                append(cur.d("wind_speed_10m")?.roundToInt()).append(' ').append(wind).append('\n')
            }
            listOf("Today", "Tomorrow").forEachIndexed { i, label ->
                val hi = (dailyAt("temperature_2m_max", i) as? JsonPrimitive)?.doubleOrNull ?: return@forEachIndexed
                val lo = (dailyAt("temperature_2m_min", i) as? JsonPrimitive)?.doubleOrNull
                val rain = (dailyAt("precipitation_probability_max", i) as? JsonPrimitive)?.intOrNull
                val code = (dailyAt("weather_code", i) as? JsonPrimitive)?.intOrNull
                append(label).append(": ").append(describeWeather(code)).append(", high ").append(hi.roundToInt()).append(unit)
                if (lo != null) append(", low ").append(lo.roundToInt()).append(unit)
                if (rain != null) append(", ").append(rain).append("% chance of rain")
                if (i == 0) {
                    val rise = (dailyAt("sunrise", 0) as? JsonPrimitive)?.contentOrNull?.substringAfter('T')
                    val set = (dailyAt("sunset", 0) as? JsonPrimitive)?.contentOrNull?.substringAfter('T')
                    if (rise != null && set != null) append(", sunrise ").append(rise).append(", sunset ").append(set)
                }
                append('\n')
            }
        }.trim()
    }

    private fun getJson(url: String, nominatim: Boolean): JsonElement? {
        if (nominatim) {
            // Nominatim's usage policy: at most one request per second.
            synchronized(this) {
                val wait = lastNominatimCall + 1100 - System.currentTimeMillis()
                if (wait > 0) Thread.sleep(wait)
                lastNominatimCall = System.currentTimeMillis()
            }
        }
        val req = Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("map service answered HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            return if (text.isBlank()) null else json.parseToJsonElement(text)
        }
    }

    companion object {
        fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(h))
        }

        fun formatDistance(meters: Double, imperial: Boolean): String = if (imperial) {
            val miles = meters / 1609.344
            if (miles < 0.2) "${(meters * 3.28084 / 10).roundToInt() * 10} ft" else String.format(Locale.US, "%.1f mi", miles)
        } else {
            if (meters < 1000) "${(meters / 10).roundToInt() * 10} m" else String.format(Locale.US, "%.1f km", meters / 1000)
        }

        fun formatDuration(seconds: Double): String {
            val min = (seconds / 60).roundToInt().coerceAtLeast(1)
            return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
        }

        /** Open/closed line for a place, from Google's live flag or parsed OSM hours. */
        fun openLine(place: Place, now: LocalDateTime = LocalDateTime.now()): String? {
            place.openNow?.let { return if (it) "open now" else "closed now" }
            val raw = place.openingHours ?: return null
            return OpeningHours.parse(raw)?.stateAt(now)?.detail ?: "hours: $raw"
        }

        fun describeWeather(code: Int?): String = when (code) {
            0 -> "clear"
            1 -> "mostly clear"
            2 -> "partly cloudy"
            3 -> "overcast"
            45, 48 -> "fog"
            51, 53, 55 -> "drizzle"
            56, 57 -> "freezing drizzle"
            61 -> "light rain"
            63 -> "rain"
            65 -> "heavy rain"
            66, 67 -> "freezing rain"
            71 -> "light snow"
            73 -> "snow"
            75 -> "heavy snow"
            77 -> "snow grains"
            80, 81 -> "rain showers"
            82 -> "violent rain showers"
            85, 86 -> "snow showers"
            95 -> "thunderstorm"
            96, 99 -> "thunderstorm with hail"
            else -> "unknown conditions"
        }

        private fun JsonObject.s(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.d(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
    }
}
