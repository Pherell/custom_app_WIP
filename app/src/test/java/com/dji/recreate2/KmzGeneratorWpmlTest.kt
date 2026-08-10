package com.dji.recreate2

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.osmdroid.util.GeoPoint
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WPML output.
 *
 * The aircraft rejected earlier files outright - `getAvailableWaylineIDs()` came back empty and
 * the UI reported "No waylines found in KMZ. Invalid WPML format." Nothing in the app noticed
 * until a mission failed to start. Every assertion here corresponds to a defect that shipped.
 *
 * The builders are called directly, so no Android Context and no filesystem are involved.
 */
class KmzGeneratorWpmlTest {

    private val defaultLocale: Locale = Locale.getDefault()

    @After
    fun tearDown() {
        Locale.setDefault(defaultLocale)
    }

    // ---------------------------------------------------------------- structure

    @Test
    fun `the wayline document is well formed`() {
        val doc = parse(KmzGenerator.buildWaylinesWpml(route(3), 5.0, intervalPhoto = false))
        assertEquals("kml", doc.documentElement.localName)
    }

    @Test
    fun `the template document is well formed`() {
        val doc = parse(KmzGenerator.buildTemplateKml(50.0, 5.0, signalLossAction = 0))
        assertEquals("kml", doc.documentElement.localName)
        assertEquals("waypoint", text(doc, "templateType"))
    }

    @Test
    fun `the wayline carries every element the schema requires`() {
        // These were ALL missing. Their absence is why the aircraft refused the file.
        val doc = parse(KmzGenerator.buildWaylinesWpml(route(3), 5.0, intervalPhoto = false))

        assertNotNull("missionConfig", element(doc, "missionConfig"))
        assertEquals("relativeToStartPoint", text(doc, "executeHeightMode"))
        assertEquals("0", text(doc, "waylineId"))
        assertNotNull("distance", text(doc, "distance"))
        assertNotNull("duration", text(doc, "duration"))
        assertNotNull("autoFlightSpeed", text(doc, "autoFlightSpeed"))
        assertNotNull("droneEnumValue", text(doc, "droneEnumValue"))
    }

    @Test
    fun `every waypoint becomes one placemark in order`() {
        val doc = parse(KmzGenerator.buildWaylinesWpml(route(5), 5.0, intervalPhoto = false))

        assertEquals(5, doc.getElementsByTagName("Placemark").length)
        assertEquals(listOf("0", "1", "2", "3", "4"), texts(doc, "index"))
    }

    @Test
    fun `route distance and duration agree with the cruise speed`() {
        // Two points 0.01 degrees of latitude apart is about 1111 m.
        val doc = parse(KmzGenerator.buildWaylinesWpml(route(2), 10.0, intervalPhoto = false))

        val distance = text(doc, "distance")!!.toDouble()
        val duration = text(doc, "duration")!!.toDouble()

        assertEquals(1111.0, distance, 5.0)
        assertEquals(distance / 10.0, duration, 0.01)
    }

    @Test
    fun `a single waypoint route has no length`() {
        val doc = parse(KmzGenerator.buildWaylinesWpml(route(1), 5.0, intervalPhoto = false))
        assertEquals(0.0, text(doc, "distance")!!.toDouble(), 0.0)
    }

    // ---------------------------------------------------------------- interval photo

    @Test
    fun `a transit mission does not shoot continuously`() {
        // THE BUG. The interval group was emitted unconditionally, so a plain three-waypoint
        // transit shot a photo every second for the whole flight and filled the SD card.
        val doc = parse(KmzGenerator.buildWaylinesWpml(route(3), 5.0, intervalPhoto = false))
        assertEquals(0, actuators(doc, "shootPhotoTimeInterval"))
    }

    @Test
    fun `a survey mission shoots on one interval group`() {
        val doc = parse(KmzGenerator.buildWaylinesWpml(route(3), 5.0, intervalPhoto = true))
        assertEquals(1, actuators(doc, "shootPhotoTimeInterval"))
        assertEquals("1", text(doc, "timeInterval"))
    }

    @Test
    fun `a survey grid does not photograph each position twice`() {
        // Regression from the first audit: waypoints carried PHOTO and the route-wide interval
        // group was enabled at the same time. They are alternatives, not additions.
        val grid = List(4) { i ->
            KmzGenerator.KmzWaypoint(
                geoPoint = GeoPoint(-6.20 + i * 0.001, 106.80),
                altitude = 80.0,
                speed = 8.0,
                actionType = "SET_GIMBAL,PHOTO",
                gimbalPitch = -90.0
            )
        }
        val doc = parse(KmzGenerator.buildWaylinesWpml(grid, 8.0, intervalPhoto = false))

        assertEquals(4, actuators(doc, "takePhoto"))
        assertEquals(0, actuators(doc, "shootPhotoTimeInterval"))
    }

    // ---------------------------------------------------------------- waypoint actions

    @Test
    fun `waypoint actions reach the wayline file at all`() {
        // The KMZ path used to discard every documented waypoint action; they only worked in
        // the unreachable Virtual-Stick fallback.
        val wps = listOf(
            waypoint(0, actionType = "START_RECORD"),
            waypoint(1, actionType = "FLY"),
            waypoint(2, actionType = "STOP_RECORD")
        )
        val doc = parse(KmzGenerator.buildWaylinesWpml(wps, 5.0, intervalPhoto = false))

        assertEquals(1, actuators(doc, "startRecord"))
        assertEquals(1, actuators(doc, "stopRecord"))
    }

    @Test
    fun `a waypoint that aims and shoots emits one gimbal move not two`() {
        // THE BUG. "SET_GIMBAL,PHOTO" is what the survey grid writes on every point, and it
        // matched two branches of the earlier per-action loop, emitting the same gimbalRotate
        // twice per waypoint.
        val wps = listOf(waypoint(0, actionType = "SET_GIMBAL,PHOTO", gimbalPitch = -60.0))
        val doc = parse(KmzGenerator.buildWaylinesWpml(wps, 5.0, intervalPhoto = false))

        assertEquals(1, actuators(doc, "gimbalRotate"))
        assertEquals(1, actuators(doc, "takePhoto"))
    }

    @Test
    fun `a plain FLY waypoint emits no action group`() {
        val doc = parse(KmzGenerator.buildWaylinesWpml(route(3), 5.0, intervalPhoto = false))
        assertEquals(0, doc.getElementsByTagNameNS(WPML, "actionGroup").length)
    }

    @Test
    fun `a dwell becomes a hover action`() {
        val wps = listOf(waypoint(0, dwellTime = 12.5), waypoint(1))
        val doc = parse(KmzGenerator.buildWaylinesWpml(wps, 5.0, intervalPhoto = false))

        assertEquals(1, actuators(doc, "hover"))
        assertEquals("12.5", text(doc, "hoverTime"))
    }

    // ---------------------------------------------------------------- gimbal protection

    @Test
    fun `a mission cannot command the gimbal past its end stop`() {
        // THE BUG. A POI beneath the aircraft drives the derived pitch to -90, past the travel
        // of every supported gimbal. The mission runs on the aircraft, so nothing on the
        // tablet can clamp it later - it has to be clamped as the file is written.
        val wps = listOf(
            KmzGenerator.KmzWaypoint(
                geoPoint = GeoPoint(-6.2000, 106.8000),
                altitude = 60.0,
                speed = 5.0,
                actionType = "LOCK_POI",
                poiTarget = GeoPoint(-6.2000, 106.8000)
            )
        )
        val doc = parse(KmzGenerator.buildWaylinesWpml(wps, 5.0, intervalPhoto = false))

        assertEquals(-85.0, text(doc, "gimbalPitchRotateAngle")!!.toDouble(), 0.001)
    }

    @Test
    fun `an explicit gimbal pitch above the range is clamped too`() {
        val wps = listOf(waypoint(0, actionType = "SET_GIMBAL", gimbalPitch = 75.0))
        val doc = parse(KmzGenerator.buildWaylinesWpml(wps, 5.0, intervalPhoto = false))

        assertEquals(25.0, text(doc, "gimbalPitchRotateAngle")!!.toDouble(), 0.001)
    }

    @Test
    fun `a pitch inside the range is written unchanged`() {
        val wps = listOf(waypoint(0, actionType = "SET_GIMBAL", gimbalPitch = -47.5))
        val doc = parse(KmzGenerator.buildWaylinesWpml(wps, 5.0, intervalPhoto = false))

        assertEquals(-47.5, text(doc, "gimbalPitchRotateAngle")!!.toDouble(), 0.001)
    }

    // ---------------------------------------------------------------- heading

    @Test
    fun `a POI waypoint keeps the nose on the target for the whole leg`() {
        // The earlier file aimed the gimbal once on arrival and never again, so the target
        // drifted out of frame as the aircraft continued. towardPOI is a continuous lock.
        val poi = GeoPoint(-6.1950, 106.8100, 12.0)
        val wps = listOf(waypoint(0, actionType = "LOCK_POI", poiTarget = poi))
        val doc = parse(KmzGenerator.buildWaylinesWpml(wps, 5.0, intervalPhoto = false))

        assertEquals("towardPOI", text(doc, "waypointHeadingMode"))
        assertEquals("-6.195,106.81,12.0", text(doc, "waypointPoiPoint"))
    }

    @Test
    fun `an explicit heading is a smooth transition`() {
        val wps = listOf(waypoint(0, heading = 137.0))
        val doc = parse(KmzGenerator.buildWaylinesWpml(wps, 5.0, intervalPhoto = false))

        assertEquals("smoothTransition", text(doc, "waypointHeadingMode"))
        assertEquals("137.0", text(doc, "waypointHeadingAngle"))
    }

    @Test
    fun `a waypoint with neither follows the wayline`() {
        val doc = parse(KmzGenerator.buildWaylinesWpml(route(2), 5.0, intervalPhoto = false))
        assertEquals(listOf("followWayline", "followWayline"), texts(doc, "waypointHeadingMode"))
    }

    @Test
    fun `a POI on a waypoint that does not use it does not change the heading`() {
        val wps = listOf(waypoint(0, actionType = "STOP_RECORD", poiTarget = GeoPoint(-6.19, 106.81)))
        val doc = parse(KmzGenerator.buildWaylinesWpml(wps, 5.0, intervalPhoto = false))

        assertEquals("followWayline", text(doc, "waypointHeadingMode"))
    }

    // ---------------------------------------------------------------- action group ids

    @Test
    fun `no two action groups share an id`() {
        // The aircraft keys actions by group id. Two groups with the same id is undefined.
        // Per-waypoint groups are numbered 100 + index, dwell groups by plain index and the
        // interval group 999, so the schemes overlap on a long route.
        val wps = List(150) { i ->
            waypoint(
                i,
                actionType = if (i == 0) "PHOTO" else "FLY",
                dwellTime = if (i == 100) 5.0 else null
            )
        }
        val doc = parse(KmzGenerator.buildWaylinesWpml(wps, 5.0, intervalPhoto = true))

        val ids = texts(doc, "actionGroupId")
        assertEquals("duplicate group ids in $ids", ids.size, ids.toSet().size)
    }

    // ---------------------------------------------------------------- formatting

    @Test
    fun `numbers use a dot on a comma decimal device`() {
        // The tablet ships to operators in locales that write 5,0. A comma inside a WPML
        // number makes the file unparseable to the aircraft.
        Locale.setDefault(Locale.GERMANY)

        val wps = listOf(waypoint(0, dwellTime = 2.5, gimbalPitch = -47.5, actionType = "SET_GIMBAL"), waypoint(1))
        val xml = KmzGenerator.buildWaylinesWpml(wps, 5.5, intervalPhoto = false)
        val doc = parse(xml)

        for (tag in listOf("distance", "duration", "autoFlightSpeed", "executeHeight",
                           "waypointSpeed", "hoverTime", "gimbalPitchRotateAngle",
                           "waypointTurnDampingDist")) {
            for (value in texts(doc, tag)) {
                assertTrue("<wpml:$tag> was '$value' under ${Locale.getDefault()}", !value.contains(','))
            }
        }
        assertTrue("coordinates must stay dot-separated", !text(doc, "distance")!!.contains(','))
    }

    // ---------------------------------------------------------------- helpers

    private fun waypoint(
        index: Int,
        actionType: String = "FLY",
        heading: Double? = null,
        dwellTime: Double? = null,
        poiTarget: GeoPoint? = null,
        gimbalPitch: Double? = null,
        movementMethod: String = "default"
    ) = KmzGenerator.KmzWaypoint(
        geoPoint = GeoPoint(-6.2000 + index * 0.001, 106.8000),
        altitude = 50.0,
        speed = 5.0,
        heading = heading,
        dwellTime = dwellTime,
        movementMethod = movementMethod,
        actionType = actionType,
        poiTarget = poiTarget,
        gimbalPitch = gimbalPitch
    )

    /** A plain transit route: [count] waypoints 0.01 degrees of latitude apart, no actions. */
    private fun route(count: Int) = List(count) { i ->
        KmzGenerator.KmzWaypoint(
            geoPoint = GeoPoint(-6.2000 + i * 0.01, 106.8000),
            altitude = 50.0,
            speed = 5.0
        )
    }

    private fun parse(xml: String): Document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

    private fun element(doc: Document, localName: String): Element? =
        doc.getElementsByTagNameNS(WPML, localName).item(0) as Element?

    private fun text(doc: Document, localName: String): String? =
        element(doc, localName)?.textContent?.trim()

    private fun texts(doc: Document, localName: String): List<String> {
        val nodes = doc.getElementsByTagNameNS(WPML, localName)
        return (0 until nodes.length).map { nodes.item(it).textContent.trim() }
    }

    /** How many actions in the document use the named actuator function. */
    private fun actuators(doc: Document, func: String): Int =
        texts(doc, "actionActuatorFunc").count { it == func }

    private companion object {
        const val WPML = "http://www.dji.com/wpmz/1.0.2"
    }
}
