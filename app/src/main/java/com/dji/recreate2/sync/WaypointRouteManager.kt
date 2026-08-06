package com.dji.recreate2.sync

import android.graphics.Color
import com.dji.recreate2.MainActivity.TacticalWaypoint
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Data class representing a named flight route profile (e.g. Waypoint_1, Waypoint_2).
 */
data class WaypointRoute(
    val id: String,
    var name: String,
    val waypoints: CopyOnWriteArrayList<TacticalWaypoint> = CopyOnWriteArrayList(),
    var isVisibleOnMap: Boolean = true,
    var isKmzRoute: Boolean = false,
    var color: Int = Color.parseColor("#00FF66")
)

/**
 * Manages multiple independent flight routes (Waypoint_1, Waypoint_2, etc.),
 * supporting selective route addition, toggling visibility, itemized route deletion,
 * and multi-route execution chaining.
 */
object WaypointRouteManager {

    private val routes = CopyOnWriteArrayList<WaypointRoute>()
    var activeRouteId: String = "route_1"
        private set

    init {
        // Initialize default primary route: Waypoint_1
        routes.add(
            WaypointRoute(
                id = "route_1",
                name = "Waypoint_1",
                color = Color.parseColor("#00FF66")
            )
        )
    }

    fun getAllRoutes(): List<WaypointRoute> = routes.toList()

    fun getActiveRoute(): WaypointRoute {
        return routes.find { it.id == activeRouteId } ?: routes.first().also { activeRouteId = it.id }
    }

    fun setActiveRoute(routeId: String): WaypointRoute? {
        val target = routes.find { it.id == routeId }
        if (target != null) {
            activeRouteId = routeId
        }
        return target
    }

    /**
     * Creates a new route profile (e.g. Waypoint_2) and sets it as active.
     */
    fun createNewRoute(customName: String? = null): WaypointRoute {
        val routeNumber = routes.size + 1
        val name = customName ?: "Waypoint_$routeNumber"
        val id = "route_${System.currentTimeMillis()}"

        // Distinct colors for different routes
        val colors = intArrayOf(
            Color.parseColor("#00FF66"), // Green
            Color.parseColor("#00E5FF"), // Cyan
            Color.parseColor("#FFCC00"), // Yellow
            Color.parseColor("#FF007F"), // Magenta
            Color.parseColor("#FF6600")  // Orange
        )
        val routeColor = colors[(routeNumber - 1) % colors.size]

        val newRoute = WaypointRoute(
            id = id,
            name = name,
            color = routeColor
        )
        routes.add(newRoute)
        activeRouteId = id
        return newRoute
    }

    /**
     * Itemized route deletion: removes ONLY the specified route by ID.
     */
    fun deleteRoute(routeId: String): Boolean {
        if (routes.size <= 1) {
            // Keep at least 1 active route profile; clear its waypoints instead of destroying it
            val last = routes.first()
            last.waypoints.clear()
            return true
        }

        val target = routes.find { it.id == routeId } ?: return false
        routes.remove(target)

        if (activeRouteId == routeId) {
            activeRouteId = routes.first().id
        }
        return true
    }

    /**
     * Toggles visibility of a specific route on the map canvas.
     */
    fun setRouteVisibility(routeId: String, isVisible: Boolean) {
        routes.find { it.id == routeId }?.isVisibleOnMap = isVisible
    }

    /**
     * Merges all visible routes into a single chained waypoints list for flight execution.
     */
    fun getChainedExecutionWaypoints(): List<TacticalWaypoint> {
        val chained = mutableListOf<TacticalWaypoint>()
        for (route in routes) {
            if (route.isVisibleOnMap && route.waypoints.isNotEmpty()) {
                chained.addAll(route.waypoints)
            }
        }
        return chained
    }

    /**
     * Master reset: clears all routes and re-initializes Waypoint_1.
     */
    fun clearAllRoutes() {
        routes.clear()
        routes.add(
            WaypointRoute(
                id = "route_1",
                name = "Waypoint_1",
                color = Color.parseColor("#00FF66")
            )
        )
        activeRouteId = "route_1"
    }
}
