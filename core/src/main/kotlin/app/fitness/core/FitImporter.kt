package app.fitness.core

import com.garmin.fit.FitDecoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.LapMesg
import com.garmin.fit.Mesg
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import java.io.ByteArrayInputStream
import java.time.Instant

/** FIT file to [ParsedActivity] with the Garmin FIT SDK. Port of `app/importers/fit_importer.py`. */
object FitImporter {
    private const val SEMICIRCLE_TO_DEG = 180.0 / 2147483648.0       // 180 / 2^31

    /** Scaled value of a field as a double, or null when the device recorded nothing (PC: `_num`). */
    private fun Mesg.num(vararg fieldNums: Int): Double? {
        for (n in fieldNums) {
            val v = getFieldDoubleValue(n)
            if (v != null && !v.isNaN()) return v
        }
        return null
    }

    fun parse(bytes: ByteArray): ParsedActivity {
        val messages = FitDecoder().decode(ByteArrayInputStream(bytes))

        val n = messages.recordMesgs.count { it.timestamp != null }
        val cols = StreamKeys.ALL.associateWith { DoubleArray(n) { Double.NaN } }
        var startFit = -1L
        var i = 0
        for (rec in messages.recordMesgs) {
            val ts = rec.timestamp?.timestamp ?: continue          // a record without a timestamp is skipped
            if (startFit < 0) startFit = ts
            cols.getValue("time")[i] = (ts - startFit).toDouble()
            rec.num(RecordMesg.HeartRateFieldNum)?.let { cols.getValue("heart_rate")[i] = it }
            rec.num(RecordMesg.PowerFieldNum)?.let { cols.getValue("power")[i] = it }
            rec.num(RecordMesg.EnhancedSpeedFieldNum, RecordMesg.SpeedFieldNum)?.let { cols.getValue("speed")[i] = it }
            rec.num(RecordMesg.CadenceFieldNum)?.let { cols.getValue("cadence")[i] = it }
            rec.num(RecordMesg.EnhancedAltitudeFieldNum, RecordMesg.AltitudeFieldNum)?.let { cols.getValue("altitude")[i] = it }
            rec.num(RecordMesg.DistanceFieldNum)?.let { cols.getValue("distance")[i] = it }
            val lat = rec.getFieldLongValue(RecordMesg.PositionLatFieldNum)
            val lon = rec.getFieldLongValue(RecordMesg.PositionLongFieldNum)
            if (lat != null && lon != null) {
                cols.getValue("lat")[i] = lat * SEMICIRCLE_TO_DEG
                cols.getValue("lon")[i] = lon * SEMICIRCLE_TO_DEG
            }
            i++
        }
        val streams = Common.keepStreams(cols)

        val session = messages.sessionMesgs.firstOrNull()
        val start = session?.startTime?.date?.toInstant()
            ?: if (startFit >= 0) com.garmin.fit.DateTime(startFit).date.toInstant()
            else error("FIT file has no timestamps; nothing to import")

        val timer = session?.num(SessionMesg.TotalTimerTimeFieldNum, SessionMesg.TotalElapsedTimeFieldNum)
            ?: cols.getValue("time").lastOrNull()?.takeIf { n > 0 }

        val distance = session?.num(SessionMesg.TotalDistanceFieldNum)
            ?: streams["distance"]?.filter { !it.isNaN() }?.maxOrNull()

        val gain = session?.num(SessionMesg.TotalAscentFieldNum)
            ?: streams["altitude"]?.let { Common.elevationGain(it) }

        val laps = messages.lapMesgs.mapIndexed { idx, lap ->
            ParsedLap(
                index = idx,
                startTime = lap.startTime?.date?.toInstant(),
                duration = lap.num(LapMesg.TotalTimerTimeFieldNum, LapMesg.TotalElapsedTimeFieldNum),
                distance = lap.num(LapMesg.TotalDistanceFieldNum),
                avgHeartRate = lap.num(LapMesg.AvgHeartRateFieldNum),
                maxHeartRate = lap.num(LapMesg.MaxHeartRateFieldNum),
                avgPower = lap.num(LapMesg.AvgPowerFieldNum),
                avgSpeed = lap.num(LapMesg.EnhancedAvgSpeedFieldNum, LapMesg.AvgSpeedFieldNum),
                elevationGain = lap.num(LapMesg.TotalAscentFieldNum),
            )
        }

        return ParsedActivity(
            sport = sportName(session),
            startTime = start,
            timerSeconds = timer,
            distance = distance,
            elevationGain = gain,
            device = device(messages.fileIdMesgs),
            streams = streams,
            laps = laps,
        )
    }

    /**
     * The PC stores the library's name for the sport ("running", "fitness_equipment"), the bare number when the
     * library does not know the value, and "unknown" when there is no sport at all.
     */
    internal fun sportName(session: SessionMesg?): String {
        val raw = session?.getFieldShortValue(SessionMesg.SportFieldNum)?.toInt() ?: return "unknown"
        return FitEnums.sport[raw] ?: raw.toString()
    }

    /**
     * PC: `_device`. The manufacturer is fitparse's name for it, or a bare number it does not know; a bare number is
     * dropped when a product name exists ("Amazfit Bip 6", not "339 Amazfit Bip 6").
     */
    internal fun device(fileIds: List<FileIdMesg>): String? {
        for (msg in fileIds) {
            val makerId = msg.manufacturer
            val makerName = makerId?.let { FitEnums.manufacturer[it] }
            val productName = msg.productName?.takeIf { it.isNotEmpty() }
            val productId = msg.product
            val product: String? = productName ?: productId?.let {
                if (makerId in FitEnums.garminProductManufacturers) FitEnums.garminProduct[it] ?: it.toString() else it.toString()
            }
            val maker = when {
                makerName != null -> makerName
                makerId != null && productName != null -> null
                else -> makerId?.toString()
            }
            val parts = listOfNotNull(maker, product)
            if (parts.isNotEmpty()) return parts.joinToString(" ")
        }
        return null
    }
}
