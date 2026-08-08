package com.dji.recreate2

import android.content.Context
import org.osmdroid.util.GeoPoint
import java.io.*
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import android.util.Log

/**
 * MSDK V5 Native KMZ (WPML) Generator
 * Mengubah rute grid (GeoPoint) menjadi file XML/KMZ berstandar DJI (Waypoint Markup Language).
 */
object KmzGenerator {

    private const val TAG = "KmzGenerator"

    /**
     * @param actionType comma-separated waypoint actions, matching MainActivity.TacticalWaypoint:
     *        FLY / PHOTO / START_RECORD / STOP_RECORD / LOCK_POI / UNLOCK_POI / SET_GIMBAL.
     * @param poiTarget  coordinate the gimbal should aim at for LOCK_POI / PHOTO.
     * @param gimbalPitch explicit gimbal pitch in degrees for SET_GIMBAL.
     */
    data class KmzWaypoint(
        val geoPoint: GeoPoint,
        val altitude: Double,
        val speed: Double,
        val heading: Double? = null,
        val dwellTime: Double? = null,
        val movementMethod: String = "default",
        val actionType: String = "FLY",
        val poiTarget: GeoPoint? = null,
        val gimbalPitch: Double? = null
    )

    /**
     * Membuat file .kmz yang siap dieksekusi oleh WaypointMissionManager DJI.
     */
    /**
     * @param intervalPhoto when true, adds a route-wide 1s interval photo action group.
     *        Only appropriate for mapping/survey runs - a plain transit mission must NOT
     *        shoot continuously (it previously did, unconditionally, filling the SD card).
     */
    fun generateMappingKmz(
        context: Context,
        waypoints: List<KmzWaypoint>,
        globalSpeed: Double,
        signalLossAction: Int = 0,
        intervalPhoto: Boolean = false
    ): File? {
        try {
            // 1. Siapkan direktori wpmz (Standard struktur DJI WPML)
            val cacheDir = context.cacheDir
            val missionDir = File(cacheDir, "wpmz")
            if (missionDir.exists()) missionDir.deleteRecursively()
            missionDir.mkdirs()

            // 2. Buat file template.kml (Metadata Misi)
            val globalAltitude = waypoints.firstOrNull()?.altitude ?: 50.0
            val templateFile = File(missionDir, "template.kml")
            templateFile.writeText(buildTemplateKml(globalAltitude, globalSpeed, signalLossAction))

            // 3. Buat file waylines.wpml (Koordinat & Aksi Kamera)
            val waylinesFile = File(missionDir, "waylines.wpml")
            waylinesFile.writeText(buildWaylinesWpml(waypoints, globalSpeed, intervalPhoto))

            // 4. Compress / ZIP folder wpmz menjadi file .kmz
            // Purge previously generated missions first - they accumulated in cacheDir forever.
            context.cacheDir.listFiles { f -> f.isFile && f.name.startsWith("Mission_M") && f.name.endsWith(".kmz") }
                ?.forEach { it.delete() }

            val kmzFileName = "Mission_M${System.currentTimeMillis()}.kmz"
            val kmzFile = File(context.cacheDir, kmzFileName)
            zipDirectory(missionDir, kmzFile)

            // 5. Bersihkan file temp XML
            missionDir.deleteRecursively()
            
            Log.d(TAG, "KMZ Generated Successfully at: ${kmzFile.absolutePath}")
            return kmzFile
            
        } catch (e: Exception) {
            Log.e(TAG, "Gagal membuat KMZ: ${e.message}")
            e.printStackTrace()
            return null
        }
    }

    private fun buildTemplateKml(globalAltitude: Double, speed: Double, signalLossAction: Int): String {
        val exitOnRCLost = "executeLostAction"
        val executeRCLostAction = when (signalLossAction) {
            0 -> "goBack"
            1 -> "landing"
            2 -> "hover"
            else -> "goBack"
        }
        return """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:wpml="http://www.dji.com/wpmz/1.0.2">
              <Document>
                <wpml:author>Recreate2_Tactical</wpml:author>
                <wpml:createTime>${System.currentTimeMillis()}</wpml:createTime>
                <wpml:updateTime>${System.currentTimeMillis()}</wpml:updateTime>
                <wpml:missionConfig>
                  <wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>
                  <wpml:finishAction>goHome</wpml:finishAction>
                  <wpml:exitOnRCLost>$exitOnRCLost</wpml:exitOnRCLost>
                  <wpml:executeRCLostAction>$executeRCLostAction</wpml:executeRCLostAction>
                  <wpml:globalTransitionalSpeed>$speed</wpml:globalTransitionalSpeed>
                  <wpml:droneInfo>
                    <wpml:droneEnumValue>67</wpml:droneEnumValue> <!-- 67 = Mavic 3 Enterprise Series -->
                    <wpml:droneSubEnumValue>1</wpml:droneSubEnumValue>
                  </wpml:droneInfo>
                </wpml:missionConfig>
                <Folder>
                  <wpml:templateType>waypoint</wpml:templateType>
                  <wpml:templateId>0</wpml:templateId>
                  <wpml:autoFlightSpeed>$speed</wpml:autoFlightSpeed>
                </Folder>
              </Document>
            </kml>
        """.trimIndent()
    }

    /** Renders a single <wpml:action> block. */
    private fun buildAction(actionId: Int, actuatorFunc: String, paramXml: String): String {
        return """
                    <wpml:action>
                      <wpml:actionId>$actionId</wpml:actionId>
                      <wpml:actionActuatorFunc>$actuatorFunc</wpml:actionActuatorFunc>
                      <wpml:actionActuatorFuncParam>
${paramXml.prependIndent("                        ")}
                      </wpml:actionActuatorFuncParam>
                    </wpml:action>
        """.trimIndent() + "\n"
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return earthRadius * c
    }

    private fun buildWaylinesWpml(
        waypoints: List<KmzWaypoint>,
        globalSpeed: Double,
        intervalPhoto: Boolean
    ): String {
        // Total route length, required by wpml:distance.
        var routeDistance = 0.0
        for (i in 1 until waypoints.size) {
            routeDistance += calculateDistance(
                waypoints[i - 1].geoPoint.latitude, waypoints[i - 1].geoPoint.longitude,
                waypoints[i].geoPoint.latitude, waypoints[i].geoPoint.longitude
            )
        }
        val cruiseSpeed = if (globalSpeed > 0.1) globalSpeed else 5.0
        val routeDuration = routeDistance / cruiseSpeed

        val sb = StringBuilder()
        // wpml:missionConfig, executeHeightMode, autoFlightSpeed, distance and duration are
        // REQUIRED by the WPML schema. Omitting them made the aircraft reject the file, which
        // is why getAvailableWaylineIDs() came back empty and the UI reported
        // "No waylines found in KMZ. Invalid WPML format."
        sb.append("""
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:wpml="http://www.dji.com/wpmz/1.0.2">
              <Document>
                <wpml:missionConfig>
                  <wpml:flyToWaylineMode>safely</wpml:flyToWaylineMode>
                  <wpml:finishAction>goHome</wpml:finishAction>
                  <wpml:exitOnRCLost>executeLostAction</wpml:exitOnRCLost>
                  <wpml:executeRCLostAction>goBack</wpml:executeRCLostAction>
                  <wpml:globalTransitionalSpeed>$cruiseSpeed</wpml:globalTransitionalSpeed>
                  <wpml:droneInfo>
                    <wpml:droneEnumValue>67</wpml:droneEnumValue>
                    <wpml:droneSubEnumValue>1</wpml:droneSubEnumValue>
                  </wpml:droneInfo>
                </wpml:missionConfig>
                <Folder>
                  <wpml:templateId>0</wpml:templateId>
                  <wpml:executeHeightMode>relativeToStartPoint</wpml:executeHeightMode>
                  <wpml:waylineId>0</wpml:waylineId>
                  <wpml:distance>${String.format(java.util.Locale.US, "%.2f", routeDistance)}</wpml:distance>
                  <wpml:duration>${String.format(java.util.Locale.US, "%.2f", routeDuration)}</wpml:duration>
                  <wpml:autoFlightSpeed>$cruiseSpeed</wpml:autoFlightSpeed>
        """.trimIndent() + "\n")

        // 1. Generate Placemarks
        waypoints.forEachIndexed { index, wp ->
            val headingBlock = if (wp.heading != null) {
                """
                  <wpml:waypointHeadingParam>
                    <wpml:waypointHeadingMode>smoothTransition</wpml:waypointHeadingMode>
                    <wpml:waypointHeadingAngle>${wp.heading}</wpml:waypointHeadingAngle>
                  </wpml:waypointHeadingParam>
                """.trimIndent()
            } else {
                """
                  <wpml:waypointHeadingParam>
                    <wpml:waypointHeadingMode>followWayline</wpml:waypointHeadingMode>
                  </wpml:waypointHeadingParam>
                """.trimIndent()
            }

            // Calculate dynamic turn damping distance
            val distToPrev = if (index > 0) {
                calculateDistance(
                    waypoints[index - 1].geoPoint.latitude,
                    waypoints[index - 1].geoPoint.longitude,
                    wp.geoPoint.latitude,
                    wp.geoPoint.longitude
                )
            } else Double.MAX_VALUE

            val distToNext = if (index < waypoints.size - 1) {
                calculateDistance(
                    wp.geoPoint.latitude,
                    wp.geoPoint.longitude,
                    waypoints[index + 1].geoPoint.latitude,
                    waypoints[index + 1].geoPoint.longitude
                )
            } else Double.MAX_VALUE

            val dampingDist = minOf(15.0, distToPrev / 3.0, distToNext / 3.0).coerceAtLeast(0.5)

            val turnBlock = if (wp.movementMethod.equals("spline", ignoreCase = true) || wp.movementMethod.equals("orbit", ignoreCase = true)) {
                """
                  <wpml:waypointTurnParam>
                    <wpml:waypointTurnMode>coordinateTurn</wpml:waypointTurnMode>
                    <wpml:waypointTurnDampingDist>${String.format(java.util.Locale.US, "%.1f", dampingDist)}</wpml:waypointTurnDampingDist>
                  </wpml:waypointTurnParam>
                """.trimIndent()
            } else {
                """
                  <wpml:waypointTurnParam>
                    <wpml:waypointTurnMode>toPointAndStopWithDiscontinuityAngle</wpml:waypointTurnMode>
                  </wpml:waypointTurnParam>
                """.trimIndent()
            }

            sb.append("""
                <Placemark>
                  <wpml:index>$index</wpml:index>
                  <Point>
                    <coordinates>${wp.geoPoint.longitude},${wp.geoPoint.latitude}</coordinates>
                  </Point>
                  <wpml:executeHeight>${wp.altitude}</wpml:executeHeight>
                  <wpml:waypointSpeed>${wp.speed}</wpml:waypointSpeed>
${headingBlock.prependIndent("                  ")}
${turnBlock.prependIndent("                  ")}
                </Placemark>
            """.trimIndent() + "\n")
        }

        // 2a. Per-waypoint camera / gimbal actions.
        // These used to be dropped entirely: KmzWaypoint carried no actionType, so every
        // documented waypoint action (PHOTO, START_RECORD, STOP_RECORD, LOCK_POI, SET_GIMBAL)
        // was silently discarded on the KMZ path and only worked in the unreachable
        // Virtual-Stick fallback.
        waypoints.forEachIndexed { index, wp ->
            val actions = wp.actionType.split(",")
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() && it != "FLY" }
            if (actions.isEmpty()) return@forEachIndexed

            val actionXml = StringBuilder()
            var actionId = 0

            for (action in actions) {
                when (action) {
                    "LOCK_POI", "PHOTO", "SET_GIMBAL" -> {
                        // Aim the gimbal first: explicit pitch if given, else derive it from
                        // the POI using the waypoint's own altitude as the height above target.
                        val pitch = wp.gimbalPitch ?: wp.poiTarget?.let { poi ->
                            val groundDist = calculateDistance(
                                wp.geoPoint.latitude, wp.geoPoint.longitude,
                                poi.latitude, poi.longitude
                            )
                            Math.toDegrees(Math.atan2(-wp.altitude, groundDist))
                        }
                        if (pitch != null) {
                            actionXml.append(buildAction(actionId++, "gimbalRotate", """
                                <wpml:gimbalRotateMode>absoluteAngle</wpml:gimbalRotateMode>
                                <wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable>
                                <wpml:gimbalPitchRotateAngle>${String.format(java.util.Locale.US, "%.1f", pitch.coerceIn(-90.0, 30.0))}</wpml:gimbalPitchRotateAngle>
                                <wpml:gimbalRollRotateEnable>0</wpml:gimbalRollRotateEnable>
                                <wpml:gimbalRollRotateAngle>0</wpml:gimbalRollRotateAngle>
                                <wpml:gimbalYawRotateEnable>0</wpml:gimbalYawRotateEnable>
                                <wpml:gimbalYawRotateAngle>0</wpml:gimbalYawRotateAngle>
                                <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
                            """.trimIndent()))
                        }
                        if (action == "PHOTO") {
                            actionXml.append(buildAction(actionId++, "takePhoto", """
                                <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
                            """.trimIndent()))
                        }
                    }
                    "START_RECORD" -> actionXml.append(buildAction(actionId++, "startRecord", """
                        <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
                    """.trimIndent()))
                    "STOP_RECORD" -> actionXml.append(buildAction(actionId++, "stopRecord", """
                        <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
                    """.trimIndent()))
                    "UNLOCK_POI" -> actionXml.append(buildAction(actionId++, "gimbalRotate", """
                        <wpml:gimbalRotateMode>absoluteAngle</wpml:gimbalRotateMode>
                        <wpml:gimbalPitchRotateEnable>1</wpml:gimbalPitchRotateEnable>
                        <wpml:gimbalPitchRotateAngle>0</wpml:gimbalPitchRotateAngle>
                        <wpml:gimbalRollRotateEnable>0</wpml:gimbalRollRotateEnable>
                        <wpml:gimbalRollRotateAngle>0</wpml:gimbalRollRotateAngle>
                        <wpml:gimbalYawRotateEnable>0</wpml:gimbalYawRotateEnable>
                        <wpml:gimbalYawRotateAngle>0</wpml:gimbalYawRotateAngle>
                        <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
                    """.trimIndent()))
                }
            }

            if (actionXml.isNotEmpty()) {
                // Offset the group id so it cannot collide with the dwell groups below.
                sb.append("""
                  <wpml:actionGroup>
                    <wpml:actionGroupId>${100 + index}</wpml:actionGroupId>
                    <wpml:actionGroupStartIndex>$index</wpml:actionGroupStartIndex>
                    <wpml:actionGroupEndIndex>$index</wpml:actionGroupEndIndex>
                    <wpml:actionGroupMode>sequence</wpml:actionGroupMode>
                    <wpml:actionTrigger>
                      <wpml:actionTriggerType>reachPoint</wpml:actionTriggerType>
                    </wpml:actionTrigger>
                """.trimIndent() + "\n")
                sb.append(actionXml)
                sb.append("                  </wpml:actionGroup>\n")
            }
        }

        // 2b. Generate dwell (hover) ActionGroups as Folder siblings of Placemarks
        waypoints.forEachIndexed { index, wp ->
            if (wp.dwellTime != null && wp.dwellTime > 0) {
                sb.append("""
                  <wpml:actionGroup>
                    <wpml:actionGroupId>$index</wpml:actionGroupId>
                    <wpml:actionGroupStartIndex>$index</wpml:actionGroupStartIndex>
                    <wpml:actionGroupEndIndex>$index</wpml:actionGroupEndIndex>
                    <wpml:actionGroupMode>sequence</wpml:actionGroupMode>
                    <wpml:actionTrigger>
                      <wpml:actionTriggerType>reachPoint</wpml:actionTriggerType>
                    </wpml:actionTrigger>
                    <wpml:action>
                      <wpml:actionId>0</wpml:actionId>
                      <wpml:actionActuatorFunc>hover</wpml:actionActuatorFunc>
                      <wpml:actionActuatorFuncParam>
                        <wpml:hoverTime>${wp.dwellTime}</wpml:hoverTime>
                      </wpml:actionActuatorFuncParam>
                    </wpml:action>
                  </wpml:actionGroup>
                """.trimIndent() + "\n")
            }
        }

        // Interval photo action group - ONLY for mapping/survey runs. This used to be emitted
        // unconditionally, so even a plain 3-waypoint transit shot a photo every second for
        // the whole flight.
        if (intervalPhoto && waypoints.isNotEmpty()) {
            sb.append("""
                  <!-- Camera Action: Interval 1 detik di seluruh rute -->
                  <wpml:actionGroup>
                    <wpml:actionGroupId>999</wpml:actionGroupId>
                    <wpml:actionGroupStartIndex>0</wpml:actionGroupStartIndex>
                    <wpml:actionGroupEndIndex>${waypoints.lastIndex}</wpml:actionGroupEndIndex>
                    <wpml:actionGroupMode>sequence</wpml:actionGroupMode>
                    <wpml:actionTrigger>
                      <wpml:actionTriggerType>betweenAdjacentPoints</wpml:actionTriggerType>
                    </wpml:actionTrigger>
                    <wpml:action>
                      <wpml:actionId>0</wpml:actionId>
                      <wpml:actionActuatorFunc>shootPhotoTimeInterval</wpml:actionActuatorFunc>
                      <wpml:actionActuatorFuncParam>
                        <wpml:payloadPositionIndex>0</wpml:payloadPositionIndex>
                        <wpml:timeInterval>1</wpml:timeInterval>
                      </wpml:actionActuatorFuncParam>
                    </wpml:action>
                  </wpml:actionGroup>
            """.trimIndent() + "\n")
        }

        sb.append("""
                </Folder>
              </Document>
            </kml>
        """.trimIndent())

        return sb.toString()
    }

    private fun zipDirectory(sourceDir: File, outputFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zos ->
            sourceDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    // Struktur file harus berada di dalam root folder ZIP bernama "wpmz/"
                    val entry = ZipEntry("wpmz/" + file.name)
                    zos.putNextEntry(entry)
                    file.inputStream().use { fis ->
                        fis.copyTo(zos)
                    }
                    zos.closeEntry()
                }
            }
        }
    }
}
