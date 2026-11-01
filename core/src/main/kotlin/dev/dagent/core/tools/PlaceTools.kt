package dev.dagent.core.tools

import dev.dagent.core.agent.Args
import dev.dagent.core.agent.Tool
import dev.dagent.core.agent.ToolContext
import dev.dagent.core.agent.ToolResult
import dev.dagent.core.agent.schema
import dev.dagent.core.agent.title
import dev.dagent.core.places.GeoPoint
import dev.dagent.core.places.PlacesClient

private const val NO_LOCATION = "Location is off or not allowed. Ask the person to allow location for D-Agent, or to name a place."

/** Finds nearby businesses with distance, drive time and open/closed status (OpenStreetMap, or Google with a key). */
class PlacesTool(
    private val places: PlacesClient,
    private val locate: suspend () -> GeoPoint?,
    private val imperial: () -> Boolean = { true },
) : Tool {
    override val name = "places"
    override val description =
        "Find places (restaurants, shops, gas stations, a business by name) near the person or near a named place, with distance, drive time and whether they are open now. " +
            "action distance: how far an address or place is."
    override val parameters = schema {
        title()
        string("action", "search (default) or distance.", enum = listOf("search", "distance"))
        string("query", "What to find, or the place to measure to.", required = true)
        string("near", "A place name to search around; default is where the phone is.")
        integer("limit", "How many results, 1-10 (default 5).")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val query = args.requireString("query")
        val nearName = args.string("near")?.takeIf { it.isNotBlank() }
        val origin = try {
            if (nearName != null) places.geocode(nearName) ?: return ToolResult.error("Couldn't find \"$nearName\".") else locate()
        } catch (e: Exception) {
            return ToolResult.error("Map lookup failed: ${e.message}")
        } ?: return ToolResult.error(NO_LOCATION)
        val units = imperial()
        return try {
            if (args.string("action") == "distance") distance(origin, query, units)
            else search(origin, query, args.int("limit") ?: 5, units, nearName)
        } catch (e: Exception) {
            ToolResult.error("Map lookup failed: ${e.message}")
        }
    }

    private suspend fun search(origin: GeoPoint, query: String, limit: Int, imperial: Boolean, nearName: String?): ToolResult {
        val found = places.search(query, origin, limit.coerceIn(1, 10))
        if (found.isEmpty()) return ToolResult.ok("No places matching \"$query\" found near ${nearName ?: "the person"}. Try a broader term or web_search.")
        val out = StringBuilder("Places matching \"$query\" near ${nearName ?: "you"} (closest first):\n")
        found.forEachIndexed { i, p ->
            val meters = PlacesClient.distanceMeters(origin, p.point)
            val route = if (i < 3 && meters < 150_000) places.drive(origin, p.point) else null
            out.append(i + 1).append(". ").append(p.name)
            if (p.category.isNotBlank()) out.append(" (").append(p.category).append(')')
            out.append(" · ").append(PlacesClient.formatDistance(route?.meters ?: meters, imperial))
            if (route != null) out.append(", ").append(PlacesClient.formatDuration(route.seconds)).append(" drive")
            if (meters < 2_000) out.append(", about ").append(PlacesClient.formatDuration(meters / 1.35)).append(" walk")
            PlacesClient.openLine(p)?.let { out.append(" · ").append(it) }
            if (p.rating != null) out.append(" · ★").append(p.rating).append(p.ratingCount?.let { " ($it)" }.orEmpty())
            out.append('\n')
            if (p.address.isNotBlank()) out.append("   ").append(p.address).append('\n')
            p.phone?.let { out.append("   phone ").append(it).append('\n') }
            if (p.openNow == null && p.openingHours == null && p.hoursText.isEmpty()) out.append("   hours unknown\n")
        }
        out.append(if (found.any { it.openNow != null }) "Source: Google Places." else "Source: OpenStreetMap; hours can be out of date.")
        return ToolResult.ok(out.toString().trim())
    }

    private suspend fun distance(origin: GeoPoint, target: String, imperial: Boolean): ToolResult {
        val dest = places.search(target, origin, 1).firstOrNull()?.point ?: places.geocode(target)
            ?: return ToolResult.error("Couldn't find \"$target\".")
        val straight = PlacesClient.distanceMeters(origin, dest)
        val route = places.drive(origin, dest)
        return ToolResult.ok(buildString {
            append("\"").append(target).append("\" is ").append(PlacesClient.formatDistance(straight, imperial)).append(" away in a straight line")
            if (route != null) {
                append(", ").append(PlacesClient.formatDistance(route.meters, imperial)).append(" by road (about ")
                    .append(PlacesClient.formatDuration(route.seconds)).append(" driving without traffic)")
            }
            append('.')
        })
    }
}

/** Current weather and the forecast for where the phone is or a named place (Open-Meteo, no key). */
class WeatherTool(
    private val places: PlacesClient,
    private val locate: suspend () -> GeoPoint?,
    private val imperial: () -> Boolean = { true },
) : Tool {
    override val name = "weather"
    override val description = "Current weather and today/tomorrow forecast for where the phone is or a named place."
    override val parameters = schema {
        title()
        string("place", "City or place; leave out for the phone's location.")
    }

    override suspend fun run(args: Args, ctx: ToolContext): ToolResult {
        val placeName = args.string("place")?.takeIf { it.isNotBlank() }
        return try {
            val point = (if (placeName != null) places.geocode(placeName) ?: return ToolResult.error("Couldn't find \"$placeName\".") else locate())
                ?: return ToolResult.error(NO_LOCATION)
            ToolResult.ok("Weather for ${placeName ?: "the phone's location"}:\n" + places.weather(point, imperial()) + "\nSource: Open-Meteo.")
        } catch (e: Exception) {
            ToolResult.error("Weather lookup failed: ${e.message}")
        }
    }
}
