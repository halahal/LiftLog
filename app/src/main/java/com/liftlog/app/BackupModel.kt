package com.liftlog.app

import android.util.JsonReader
import android.util.JsonToken
import java.io.StringReader
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

internal data class BackupRows(
    val weekly: List<WeekRow>,
    val overrides: List<OverrideRow>,
    val sessions: List<SessionWithSets>,
)

internal object BackupModel {
    private const val FORMAT = "liftlog-backup"
    private const val VERSION = 1
    private const val MAX_CHARS = 8 * 1024 * 1024

    fun encode(rows: BackupRows): String = JSONObject().apply {
        put("format", FORMAT)
        put("version", VERSION)
        put("weekly", jsonArray(rows.weekly) {
            JSONObject().put("weekday", it.weekday).put("plan", JSONArray(it.planJson))
        })
        put("overrides", jsonArray(rows.overrides) {
            JSONObject().put("date", it.date).put("plan", JSONArray(it.planJson))
        })
        put("sessions", jsonArray(rows.sessions) { (session, records) ->
            JSONObject()
                .put("date", session.date)
                .put("plan", JSONArray(session.planJson))
                .put("ended", session.ended)
                .put("sets", jsonArray(records.sortedWith(compareBy({ it.exerciseIndex }, { it.setIndex }))) {
                    JSONObject()
                        .put("exerciseIndex", it.exerciseIndex)
                        .put("setIndex", it.setIndex)
                        .put("weight", it.weight)
                        .put("reps", it.reps)
                })
        })
    }.toString().also { require(it.length <= MAX_CHARS) { "备份文件超过首版的大小限制。" } }

    fun decode(json: String): BackupRows {
        require(json.length in 1..MAX_CHARS) { "备份为空或超过大小限制。" }
        return try {
            checkJsonStructure(json)
            val tokener = org.json.JSONTokener(json.trimEnd())
            val root = tokener.nextValue() as? JSONObject ?: error("object")
            require(!tokener.more())
            require(root.getString("format") == FORMAT && root.checkedInt("version") == VERSION)

            val weekdays = mutableSetOf<Int>()
            val weekly = root.getJSONArray("weekly").objects(7).map { row ->
                val key = row.checkedInt("weekday")
                require(key in 1..7 && weekdays.add(key))
                WeekRow(key, checkedPlan(row).first)
            }
            val overrideDates = mutableSetOf<String>()
            val overrides = root.getJSONArray("overrides").objects(36_600).map { row ->
                val key = checkedDate(row)
                require(overrideDates.add(key))
                OverrideRow(key, checkedPlan(row).first)
            }
            val sessionDates = mutableSetOf<String>()
            val sessions = root.getJSONArray("sessions").objects(36_600).map { row ->
                val date = checkedDate(row)
                require(sessionDates.add(date))
                val (plan, planSize) = checkedPlan(row)
                val ended = row.get("ended") as? Boolean ?: error("boolean")
                require(planSize > 0)
                val keys = mutableSetOf<Pair<Int, Int>>()
                val records = row.getJSONArray("sets").objects(planSize * 200).map { record ->
                    val exerciseIndex = record.checkedInt("exerciseIndex")
                    val setIndex = record.checkedInt("setIndex")
                    val weight = record.checkedWeight()
                    val reps = record.checkedInt("reps")
                    require(exerciseIndex in 0 until planSize && setIndex in 0 until 200)
                    require(reps in 1..999 && keys.add(exerciseIndex to setIndex))
                    SetLog(date, exerciseIndex, setIndex, weight, reps)
                }
                SessionWithSets(SessionRow(date, plan, ended), records)
            }
            BackupRows(weekly, overrides, sessions)
        } catch (e: Exception) {
            throw IllegalArgumentException("文件不是有效的练迹备份，或备份版本不受支持。原数据未修改。", e)
        }
    }

    // Keep the platform's permissive JSONObject parser away from deeply nested or ambiguous inputs.
    // This pass uses an explicit, bounded stack and rejects duplicate fields and trailing content.
    private fun checkJsonStructure(json: String) {
        JsonReader(StringReader(json)).use { reader ->
            val containers = ArrayList<MutableSet<String>?>()
            require(reader.peek() == JsonToken.BEGIN_OBJECT)
            do {
                when (reader.peek()) {
                    JsonToken.BEGIN_OBJECT -> {
                        require(containers.size < 16)
                        containers.add(mutableSetOf())
                        reader.beginObject()
                    }
                    JsonToken.BEGIN_ARRAY -> {
                        require(containers.size < 16)
                        containers.add(null)
                        reader.beginArray()
                    }
                    JsonToken.END_OBJECT -> {
                        reader.endObject()
                        containers.removeAt(containers.lastIndex)
                    }
                    JsonToken.END_ARRAY -> {
                        reader.endArray()
                        containers.removeAt(containers.lastIndex)
                    }
                    JsonToken.NAME -> {
                        require(containers.last()?.add(reader.nextName()) == true)
                    }
                    JsonToken.STRING, JsonToken.NUMBER, JsonToken.BOOLEAN, JsonToken.NULL -> reader.skipValue()
                    else -> error("unexpected JSON token")
                }
            } while (containers.isNotEmpty())
            require(reader.peek() == JsonToken.END_DOCUMENT)
        }
    }

    private fun checkedDate(row: JSONObject): String {
        val text = row.get("date") as? String ?: error("date")
        val date = LocalDate.parse(text)
        require(date.year in 1..9999 && date.toString() == text)
        return text
    }

    private fun checkedPlan(row: JSONObject): Pair<String, Int> {
        val exercises = row.getJSONArray("plan").objects(100).map { exercise ->
            ExercisePlan(
                name = exercise.get("name") as? String ?: error("name"),
                weight = exercise.checkedWeight(),
                sets = exercise.checkedInt("sets"),
                reps = exercise.checkedInt("reps"),
                restSeconds = exercise.checkedInt("restSeconds"),
            )
        }
        return encodePlan(exercises) to exercises.size
    }

    private fun JSONObject.checkedInt(key: String): Int {
        val n = get(key) as? Number ?: error("number")
        val d = n.toDouble()
        require(d.isFinite() && d >= Int.MIN_VALUE.toDouble() && d <= Int.MAX_VALUE.toDouble())
        return d.toInt().also { require(it.toDouble() == d) }
    }

    private fun JSONObject.checkedWeight(): Double {
        val n = get("weight") as? Number ?: error("weight")
        return n.toDouble().also { require(it.isFinite() && it in 0.0..5000.0) }
    }

    private fun JSONArray.objects(max: Int): List<JSONObject> {
        require(length() <= max)
        return List(length(), ::getJSONObject)
    }

    private fun <T> jsonArray(rows: List<T>, toJson: (T) -> JSONObject): JSONArray =
        JSONArray().apply { rows.forEach { put(toJson(it)) } }
}
