package com.liftlog.app

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.withTransaction
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import org.json.JSONArray
import org.json.JSONObject

data class ExercisePlan(
    val name: String,
    val weight: Double,
    val sets: Int,
    val reps: Int,
    val restSeconds: Int,
)

@Entity(
    tableName = "set_logs",
    primaryKeys = ["date", "exerciseIndex", "setIndex"],
    foreignKeys = [ForeignKey(
        entity = SessionRow::class,
        parentColumns = ["date"],
        childColumns = ["date"],
        onDelete = ForeignKey.CASCADE,
    )],
)
data class SetLog(
    val date: String,
    val exerciseIndex: Int,
    val setIndex: Int,
    val weight: Double,
    val reps: Int,
)

data class DayLog(
    val date: String,
    val plan: List<ExercisePlan>,
    val sets: List<SetLog>,
    val ended: Boolean,
)

private fun DayLog.validSets(): List<SetLog> = sets.filter {
    it.date == date && it.exerciseIndex in plan.indices && it.setIndex >= 0 &&
        it.weight.isFinite() && it.weight >= 0 && it.reps > 0
}

enum class DayStatus { REST, PLANNED, PARTIAL, COMPLETE }

data class ExerciseStats(
    val name: String,
    val days: Int,
    val weight: Double,
    val reps: Int,
    val date: String,
)

data class AppData(
    val weekly: Map<Int, List<ExercisePlan>> = emptyMap(),
    val overrides: Map<String, List<ExercisePlan>> = emptyMap(),
    val days: Map<String, DayLog> = emptyMap(),
) {
    // An existing training session supersedes future edits to any planning source.
    fun plan(date: LocalDate): List<ExercisePlan> =
        days[date.toString()]?.plan ?: overrides[date.toString()]
        ?: weekly[date.dayOfWeek.value].orEmpty()

    fun status(date: LocalDate): DayStatus {
        val goal = plan(date)
        val valid = days[date.toString()]?.validSets().orEmpty()
        if (valid.isEmpty()) return if (goal.isEmpty()) DayStatus.REST else DayStatus.PLANNED
        val done = valid.map { it.exerciseIndex to it.setIndex }.toHashSet()
        val allPlannedRecorded = goal.isNotEmpty() && goal.withIndex().all { (index, exercise) ->
            (0 until exercise.sets).all { (index to it) in done }
        }
        return if (allPlannedRecorded) DayStatus.COMPLETE else DayStatus.PARTIAL
    }

    fun stats(month: YearMonth? = null): List<ExerciseStats> {
        data class Sample(val name: String, val log: SetLog)
        val records = days.values.asSequence()
            .filter { month == null || YearMonth.from(LocalDate.parse(it.date)) == month }
            .flatMap { day ->
                day.validSets().asSequence().map { log -> Sample(day.plan[log.exerciseIndex].name, log) }
            }
            .groupBy { it.name.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT) }
        return records.values.map { group ->
            val best = group.maxWith(
                compareBy<Sample>({ it.log.weight }, { it.log.reps }, { it.log.date })
            )
            ExerciseStats(
                name = best.name,
                days = group.map { it.log.date }.toSet().size,
                weight = best.log.weight,
                reps = best.log.reps,
                date = best.log.date,
            )
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }
}

@Entity(tableName = "weekly_plans", primaryKeys = ["weekday"])
internal data class WeekRow(val weekday: Int, val planJson: String)

@Entity(tableName = "date_overrides", primaryKeys = ["date"])
internal data class OverrideRow(val date: String, val planJson: String)

@Entity(tableName = "sessions", primaryKeys = ["date"])
internal data class SessionRow(val date: String, val planJson: String, val ended: Boolean)

internal data class SessionWithSets(
    @androidx.room.Embedded val session: SessionRow,
    @Relation(parentColumn = "date", entityColumn = "date") val sets: List<SetLog>,
)

@Dao
internal interface WorkoutDao {
    @Query("SELECT * FROM weekly_plans") fun weekly(): Flow<List<WeekRow>>
    @Query("SELECT * FROM date_overrides") fun overrides(): Flow<List<OverrideRow>>
    @Transaction @Query("SELECT * FROM sessions") fun sessions(): Flow<List<SessionWithSets>>

    @Query("SELECT * FROM weekly_plans ORDER BY weekday") suspend fun listWeekly(): List<WeekRow>
    @Query("SELECT * FROM date_overrides ORDER BY date") suspend fun listOverrides(): List<OverrideRow>
    @Transaction @Query("SELECT * FROM sessions ORDER BY date") suspend fun listSessions(): List<SessionWithSets>

    @Query("DELETE FROM weekly_plans") suspend fun clearWeekly()
    @Query("DELETE FROM date_overrides") suspend fun clearOverrides()
    @Query("DELETE FROM sessions") suspend fun clearSessions()

    @Query("SELECT * FROM weekly_plans WHERE weekday = :weekday")
    suspend fun weekly(weekday: Int): WeekRow?

    @Query("SELECT * FROM date_overrides WHERE date = :date")
    suspend fun dateOverride(date: String): OverrideRow?

    @Query("SELECT * FROM sessions WHERE date = :date")
    suspend fun session(date: String): SessionRow?

    @Query("SELECT * FROM set_logs WHERE date = :date AND exerciseIndex = :exerciseIndex AND setIndex = :setIndex")
    suspend fun log(date: String, exerciseIndex: Int, setIndex: Int): SetLog?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: WeekRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: OverrideRow)

    @Insert
    suspend fun insert(row: SessionRow)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: SetLog)

    @Query("DELETE FROM date_overrides WHERE date = :date")
    suspend fun deleteOverride(date: String)

    @Query("DELETE FROM set_logs WHERE date = :date AND exerciseIndex = :exerciseIndex AND setIndex = :setIndex")
    suspend fun deleteLog(date: String, exerciseIndex: Int, setIndex: Int)

    // Never REPLACE a parent session: SQLite would delete its associated sets.
    @Query("UPDATE sessions SET ended = :ended WHERE date = :date")
    suspend fun markEnded(date: String, ended: Boolean)
}

@Database(
    entities = [WeekRow::class, OverrideRow::class, SessionRow::class, SetLog::class],
    version = 1,
    exportSchema = true,
)
internal abstract class WorkoutDatabase : RoomDatabase() {
    abstract fun dao(): WorkoutDao

    companion object {
        @Volatile private var instance: WorkoutDatabase? = null

        fun open(context: Context): WorkoutDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                WorkoutDatabase::class.java,
                "liftlog.db",
            ).build().also { instance = it }
        }
    }
}

class WorkoutRepository(context: Context) {
    private val db = WorkoutDatabase.open(context)
    private val dao = db.dao()

    val data: Flow<AppData> = combine(dao.weekly(), dao.overrides(), dao.sessions()) { weekly, overrides, sessions ->
        AppData(
            weekly = weekly.associate { it.weekday to decodePlan(it.planJson) },
            overrides = overrides.associate { it.date to decodePlan(it.planJson) },
            days = sessions.associate { (session, sets) ->
                session.date to DayLog(
                    session.date,
                    decodePlan(session.planJson),
                    sets.sortedWith(compareBy({ it.exerciseIndex }, { it.setIndex })),
                    session.ended,
                )
            },
        )
    }

    /** ISO weekday (Monday=1 to Sunday=7). */
    suspend fun saveWeekly(weekday: Int, plan: List<ExercisePlan>) {
        require(weekday in 1..7)
        dao.save(WeekRow(weekday, encodePlan(plan)))
    }

    /** An empty list is an explicit rest day; null removes the override. */
    suspend fun saveOverride(date: LocalDate, plan: List<ExercisePlan>?) {
        db.withTransaction {
            val key = date.toString()
            require(dao.session(key) == null) { "该日期的训练已开始，计划快照不可修改。" }
            if (plan == null) dao.deleteOverride(key)
            else dao.save(OverrideRow(key, encodePlan(plan)))
        }
    }

    suspend fun start(date: LocalDate) {
        require(!date.isAfter(LocalDate.now())) { "还不能记录未来日期的训练。" }
        db.withTransaction {
            if (dao.session(date.toString()) == null) {
                val source = dao.dateOverride(date.toString())?.planJson
                    ?: dao.weekly(date.dayOfWeek.value)?.planJson ?: "[]"
                require(decodePlan(source).isNotEmpty()) { "请先为这一天安排动作。" }
                dao.insert(SessionRow(date.toString(), source, ended = false))
            }
        }
    }

    /** Replaces an existing set when editing; true means a new set was recorded. */
    suspend fun set(
        date: LocalDate,
        exerciseIndex: Int,
        setIndex: Int,
        weight: Double,
        reps: Int,
        expectNew: Boolean? = null,
    ): Boolean {
        require(weight.isFinite() && weight in 0.0..5000.0 && reps in 1..999) { "请检查重量和次数。" }
        return db.withTransaction {
            require(setIndex in 0 until 200)
            val key = date.toString()
            val session = requireNotNull(dao.session(key)) { "请先开始训练。" }
            require(exerciseIndex in decodePlan(session.planJson).indices)
            val isNew = dao.log(key, exerciseIndex, setIndex) == null
            require(expectNew == null || expectNew == isNew) { "这组记录已变化，请重新选择后再试。" }
            require(!isNew || !session.ended) { "请先继续训练，再新增记录。" }
            dao.save(SetLog(key, exerciseIndex, setIndex, weight, reps))
            isNew
        }
    }

    suspend fun undo(date: LocalDate, exerciseIndex: Int, setIndex: Int) {
        dao.deleteLog(date.toString(), exerciseIndex, setIndex)
    }

    suspend fun finish(date: LocalDate) { dao.markEnded(date.toString(), true) }
    suspend fun reopen(date: LocalDate) { dao.markEnded(date.toString(), false) }

    /** Exports a coherent snapshot without writing to the database. */
    suspend fun exportBackup(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        BackupModel.encode(db.withTransaction {
            BackupRows(dao.listWeekly(), dao.listOverrides(), dao.listSessions())
        })
    }

    /** Validates the entire backup before atomically replacing the local training database. */
    suspend fun importBackup(json: String) {
        val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            BackupModel.decode(json)
        }
        db.withTransaction {
            dao.clearSessions() // Foreign keys cascade the deletion to set_logs.
            dao.clearOverrides()
            dao.clearWeekly()
            rows.weekly.forEach { dao.save(it) }
            rows.overrides.forEach { dao.save(it) }
            rows.sessions.forEach { (session, records) ->
                dao.insert(session)
                records.forEach { dao.save(it) }
            }
        }
    }
}

internal fun encodePlan(plan: List<ExercisePlan>): String {
    require(plan.size <= 100) { "每天最多安排 100 个动作。" }
    return JSONArray().apply {
        for (exercise in plan) {
            val name = exercise.name.trim()
            require(name.isNotBlank() && name.length <= 80)
            require(exercise.weight.isFinite() && exercise.weight in 0.0..5000.0)
            require(exercise.sets in 1..100 && exercise.reps in 1..999 && exercise.restSeconds in 0..3600)
            put(JSONObject().apply {
                put("name", name)
                put("weight", exercise.weight)
                put("sets", exercise.sets)
                put("reps", exercise.reps)
                put("restSeconds", exercise.restSeconds)
            })
        }
    }.toString()
}

internal fun decodePlan(json: String): List<ExercisePlan> = JSONArray(json).let { array ->
    List(array.length()) { index ->
        array.getJSONObject(index).let {
            ExercisePlan(
                name = it.getString("name"),
                weight = it.getDouble("weight"),
                sets = it.getInt("sets"),
                reps = it.getInt("reps"),
                restSeconds = it.getInt("restSeconds"),
            )
        }
    }
}
