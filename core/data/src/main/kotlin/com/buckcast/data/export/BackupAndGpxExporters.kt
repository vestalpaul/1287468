package com.buckcast.data.export

import androidx.room.withTransaction
import com.buckcast.data.local.BuckCastDatabase
import com.buckcast.data.local.entities.AppSettingsEntity
import com.buckcast.data.local.entities.FieldLogEntity
import com.buckcast.data.local.entities.SightingEntity
import com.buckcast.data.local.entities.StandEntity
import com.buckcast.data.local.entities.StandRouteEntity
import com.buckcast.data.local.entities.StandSitEntity
import com.buckcast.data.local.entities.TrackPointEntity
import com.buckcast.data.local.entities.TrackSessionEntity
import com.buckcast.data.local.entities.WaypointEntity
import com.buckcast.data.local.entities.WeaponProfileEntity
import com.buckcast.domain.math.GeoUnits
import com.buckcast.domain.repository.BackupRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.Locale

@Serializable
data class BuckCastBackupV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val exportedAtEpochMs: Long,
    val settings: AppSettingsEntity? = null,
    val stands: List<StandEntity> = emptyList(),
    val routes: List<StandRouteEntity> = emptyList(),
    val sits: List<StandSitEntity> = emptyList(),
    val waypoints: List<WaypointEntity> = emptyList(),
    val trackSessions: List<TrackSessionEntity> = emptyList(),
    val trackPoints: List<TrackPointEntity> = emptyList(),
    val fieldLogs: List<FieldLogEntity> = emptyList(),
    val sightings: List<SightingEntity> = emptyList(),
    val weaponProfiles: List<WeaponProfileEntity> = emptyList(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

object GpxTrackExporter {

    fun export(session: TrackSessionEntity, points: List<TrackPointEntity>): String {
        val sb = StringBuilder()
        val escapedName = escapeXml(session.name)
        val escapedType = escapeXml(session.trackType)
        val startIso = Instant.ofEpochMilli(session.startedAtEpochMs).toString()

        sb.appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.appendLine("""<gpx version="1.1" creator="BuckCastAndroid" xmlns="http://www.topografix.com/GPX/1/1">""")
        sb.appendLine("  <metadata>")
        sb.appendLine("    <name>$escapedName</name>")
        sb.appendLine("    <time>$startIso</time>")
        sb.appendLine("  </metadata>")
        sb.appendLine("  <trk>")
        sb.appendLine("    <name>$escapedName</name>")
        sb.appendLine("    <type>$escapedType</type>")
        if (session.notes.isNotBlank()) {
            sb.appendLine("    <desc>${escapeXml(session.notes)}</desc>")
        }
        sb.appendLine("    <trkseg>")
        for (pt in points) {
            val latStr = String.format(Locale.US, "%.7f", pt.latitude)
            val lonStr = String.format(Locale.US, "%.7f", pt.longitude)
            val ptIso = Instant.ofEpochMilli(pt.recordedAtEpochMs).toString()
            sb.appendLine("""      <trkpt lat="$latStr" lon="$lonStr">""")
            if (pt.elevationFeet != null) {
                val eleMeters = GeoUnits.feetToMeters(pt.elevationFeet)
                sb.appendLine("        <ele>${String.format(Locale.US, "%.2f", eleMeters)}</ele>")
            }
            sb.appendLine("        <time>$ptIso</time>")
            sb.appendLine("      </trkpt>")
        }
        sb.appendLine("    </trkseg>")
        sb.appendLine("  </trk>")
        sb.appendLine("</gpx>")
        return sb.toString()
    }

    private fun escapeXml(input: String): String = buildString(input.length) {
        for (ch in input) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}

internal class BackupRepositoryImpl(
    private val database: BuckCastDatabase,
    private val json: Json,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) : BackupRepository {

    override suspend fun exportBackupJson(): String {
        val payload = BuckCastBackupV1(
            schemaVersion = BuckCastBackupV1.CURRENT_SCHEMA_VERSION,
            exportedAtEpochMs = clockMs(),
            settings = database.appSettingsDao().getSettingsOnce(),
            stands = database.standDao().getAllStandsOnce(),
            routes = database.standDao().getAllRoutesOnce(),
            sits = database.standDao().getAllSitsOnce(),
            waypoints = database.waypointDao().getAllOnce(),
            trackSessions = database.trackDao().getAllSessionsOnce(),
            trackPoints = database.trackDao().getAllPointsOnce(),
            fieldLogs = database.fieldLogDao().getAllOnce(),
            sightings = database.sightingDao().getAllOnce(),
            weaponProfiles = database.weaponProfileDao().getAllOnce(),
        )
        return json.encodeToString(payload)
    }

    override suspend fun importBackupJson(json: String) {
        val payload = this.json.decodeFromString<BuckCastBackupV1>(json)
        require(payload.schemaVersion == BuckCastBackupV1.CURRENT_SCHEMA_VERSION) {
            "Unsupported backup schemaVersion: ${payload.schemaVersion}"
        }

        database.withTransaction {
            database.sightingDao().deleteAll()
            database.fieldLogDao().deleteAll()
            database.trackDao().deleteAllPoints()
            database.trackDao().deleteAllSessions()
            database.standDao().deleteAllSits()
            database.standDao().deleteAllRoutes()
            database.standDao().deleteAllStands()
            database.waypointDao().deleteAll()
            database.weaponProfileDao().deleteAll()

            payload.settings?.let {
                database.appSettingsDao().upsert(it.copy(id = AppSettingsEntity.SINGLETON_ID))
            }
            if (payload.stands.isNotEmpty()) database.standDao().upsertStands(payload.stands)
            if (payload.routes.isNotEmpty()) database.standDao().upsertRoutes(payload.routes)
            if (payload.sits.isNotEmpty()) database.standDao().upsertSits(payload.sits)
            if (payload.waypoints.isNotEmpty()) database.waypointDao().upsertAll(payload.waypoints)
            if (payload.trackSessions.isNotEmpty()) database.trackDao().upsertSessions(payload.trackSessions)
            if (payload.trackPoints.isNotEmpty()) database.trackDao().insertPoints(payload.trackPoints)
            if (payload.fieldLogs.isNotEmpty()) database.fieldLogDao().upsertAll(payload.fieldLogs)
            if (payload.sightings.isNotEmpty()) database.sightingDao().upsertAll(payload.sightings)
            if (payload.weaponProfiles.isNotEmpty()) database.weaponProfileDao().upsertAll(payload.weaponProfiles)
        }
    }

    override suspend fun exportTrackGpx(sessionId: String): String {
        val session = database.trackDao().getSessionById(sessionId)
            ?: throw IllegalArgumentException("Track session not found: $sessionId")
        val points = database.trackDao().getPointsForSessionOnce(sessionId)
        return GpxTrackExporter.export(session, points)
    }
}
