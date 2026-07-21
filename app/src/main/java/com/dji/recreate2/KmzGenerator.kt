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

    data class KmzWaypoint(val geoPoint: GeoPoint, val altitude: Double, val speed: Double, val heading: Double? = null, val dwellTime: Double? = null, val movementMethod: String = "default")

    /**
     * Membuat file .kmz yang siap dieksekusi oleh WaypointMissionManager DJI.
     */
    fun generateMappingKmz(
        context: Context,
        waypoints: List<KmzWaypoint>,
        globalSpeed: Double,
        signalLossAction: Int = 0
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
            waylinesFile.writeText(buildWaylinesWpml(waypoints))

            // 4. Compress / ZIP folder wpmz menjadi file .kmz
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

    private fun buildWaylinesWpml(waypoints: List<KmzWaypoint>): String {
        val sb = StringBuilder()
        sb.append("""
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2" xmlns:wpml="http://www.dji.com/wpmz/1.0.2">
              <Document>
                <Folder>
                  <wpml:templateId>0</wpml:templateId>
                  <wpml:waylineId>0</wpml:waylineId>
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
                    <wpml:waypointTurnDampingDist>${String.format("%.1f", dampingDist)}</wpml:waypointTurnDampingDist>
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

        // 2. Generate ActionGroups as Folder siblings of Placemarks
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

        // Always add interval photo action group if desired
        if (waypoints.isNotEmpty()) {
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
