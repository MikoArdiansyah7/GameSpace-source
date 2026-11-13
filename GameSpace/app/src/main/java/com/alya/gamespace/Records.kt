package com.alya.gamespace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class Rec(
    val id: String, val game: String, val label: String, val start: Long, val dur: Int,
    val plat: String, val model: String, val sdk: Int, val mode: Int,
    val fps: FloatArray, val temp: FloatArray, val watt: FloatArray
)

class RecStats(
    val max: Float, val min: Float, val avg: Float, val variance: Float,
    val smooth: Float, val low5: Float, val maxTemp: Float, val avgW: Float
)

object Records {
    private fun dir(c: Context): File { val d = File(c.filesDir, "records"); d.mkdirs(); return d }

    private fun arr(a: FloatArray): JSONArray {
        val j = JSONArray()
        for (v in a) j.put(Math.round(v * 10f) / 10.0)
        return j
    }
    private fun farr(j: JSONArray?): FloatArray {
        if (j == null) return FloatArray(0)
        val a = FloatArray(j.length())
        for (i in 0 until j.length()) a[i] = j.optDouble(i, 0.0).toFloat()
        return a
    }

    fun save(c: Context, r: Rec) {
        val j = JSONObject()
        j.put("id", r.id); j.put("game", r.game); j.put("label", r.label); j.put("start", r.start); j.put("dur", r.dur)
        j.put("plat", r.plat); j.put("model", r.model); j.put("sdk", r.sdk); j.put("mode", r.mode)
        j.put("fps", arr(r.fps)); j.put("temp", arr(r.temp)); j.put("watt", arr(r.watt))
        File(dir(c), r.id + ".json").writeText(j.toString())
    }

    private fun load(f: File): Rec? = try {
        val j = JSONObject(f.readText())
        Rec(j.getString("id"), j.getString("game"), j.optString("label", j.getString("game")), j.getLong("start"), j.getInt("dur"),
            j.optString("plat"), j.optString("model"), j.optInt("sdk"), j.optInt("mode", 1),
            farr(j.optJSONArray("fps")), farr(j.optJSONArray("temp")), farr(j.optJSONArray("watt")))
    } catch (_: Throwable) { null }

    fun list(c: Context): List<Rec> {
        val out = ArrayList<Rec>()
        val files = dir(c).listFiles() ?: return out
        for (f in files) if (f.name.endsWith(".json")) load(f)?.let { out.add(it) }
        out.sortWith(Comparator { a, b -> if (a.start == b.start) 0 else if (a.start < b.start) 1 else -1 })
        return out
    }

    fun delete(c: Context, id: String) { File(dir(c), "$id.json").delete() }

    fun stats(r: Rec): RecStats {
        val v = ArrayList<Float>()
        for (f in r.fps) if (f > 0f) v.add(f)
        var mx = 0f; var mn = 0f; var avg = 0f; var vr = 0f; var sm = 0f; var low = 0f
        if (v.isNotEmpty()) {
            mn = Float.MAX_VALUE
            var sum = 0f; var good = 0
            for (f in v) { if (f > mx) mx = f; if (f < mn) mn = f; sum += f; if (f >= 45f) good++ }
            avg = sum / v.size
            var s2 = 0f
            for (f in v) s2 += (f - avg) * (f - avg)
            vr = s2 / v.size
            sm = 100f * good / v.size
            val a = v.toFloatArray()
            java.util.Arrays.sort(a)
            val k = Math.max(1, Math.ceil(a.size * 0.05).toInt())
            var ls = 0f
            for (i in 0 until k) ls += a[i]
            low = ls / k
        }
        var mt = 0f
        for (t in r.temp) if (t > mt) mt = t
        var ws = 0f; var wn = 0
        for (w in r.watt) if (w > 0f) { ws += w; wn++ }
        return RecStats(mx, mn, avg, vr, sm, low, mt, if (wn > 0) ws / wn else 0f)
    }
}
