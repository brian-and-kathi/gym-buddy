package com.gymbuddy

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class TodayWorkout(
    val exercises: List<Exercise>,
    val isRest: Boolean,
    val hasRoutine: Boolean,
    val isMakeup: Boolean = false,
    val dayOfWeek: Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
)

object MakeupSession {
    private const val PREFS = "makeup_session"
    private const val KEY_DAY = "day_of_week"
    private const val KEY_DATE = "date"

    fun getDayOfWeek(context: Context): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedDate = prefs.getString(KEY_DATE, null) ?: return null
        if (storedDate != WorkoutRepository.todayDateString()) {
            clear(context)
            return null
        }
        val day = prefs.getInt(KEY_DAY, -1)
        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        if (day !in 1..7 || day == today) {
            clear(context)
            return null
        }
        return day
    }

    fun set(context: Context, dayOfWeek: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_DAY, dayOfWeek)
            .putString(KEY_DATE, WorkoutRepository.todayDateString())
            .commit()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }
}

object WorkoutRepository {
    private val gson = Gson()
    private val exerciseListType = object : TypeToken<List<Exercise>>() {}.type

    fun todayDateString(): String = dateString(System.currentTimeMillis())

    fun dateString(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))

    fun todayDayOfWeek(): Int = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)

    fun loadOrCreateToday(context: Context): TodayWorkout {
        val db = AppDatabase.getDatabase(context)
        val makeupDay = MakeupSession.getDayOfWeek(context)
        val dayOfWeek = makeupDay ?: todayDayOfWeek()
        val isMakeup = makeupDay != null
        val routine = db.routineDao().getByDayOfWeekSync(dayOfWeek)
            ?: return TodayWorkout(emptyList(), isRest = false, hasRoutine = false, isMakeup, dayOfWeek)

        if (routine.isRest) {
            return TodayWorkout(emptyList(), isRest = true, hasRoutine = true, isMakeup, dayOfWeek)
        }

        val dateStr = todayDateString()
        val existing = db.workoutLogDao().getByDate(dateStr)
        if (existing != null) {
            val logged: List<Exercise> = gson.fromJson(existing.loggedJson, exerciseListType) ?: emptyList()
            return TodayWorkout(logged, isRest = false, hasRoutine = true, isMakeup, dayOfWeek)
        }

        val exercises = routine.exercises
        saveTodayExercises(context, exercises)
        return TodayWorkout(exercises, isRest = false, hasRoutine = true, isMakeup, dayOfWeek)
    }

    /**
     * Makes [dayOfWeek] the active workout (sticky makeup, or today if it matches the calendar).
     * Replaces today's log with that day's routine only when the active day actually changes,
     * so returning to an in-progress makeup session keeps set progress.
     */
    fun switchActiveDay(context: Context, dayOfWeek: Int) {
        val today = todayDayOfWeek()
        val currentMakeup = MakeupSession.getDayOfWeek(context)
        val wantMakeup = dayOfWeek != today
        val alreadyOnThisDay = if (wantMakeup) currentMakeup == dayOfWeek else currentMakeup == null

        if (wantMakeup) {
            MakeupSession.set(context, dayOfWeek)
        } else {
            MakeupSession.clear(context)
        }

        if (alreadyOnThisDay) return
        WorkoutClock.reset(context)

        val routine = AppDatabase.getDatabase(context).routineDao().getByDayOfWeekSync(dayOfWeek) ?: return
        if (routine.isRest) return
        val fresh = routine.exercises.map { exercise ->
            exercise.copy(
                completedSets = 0,
                isTimerActive = false,
                remainingSeconds = 0,
                timerEndTime = 0,
                timerDurationSeconds = 0
            )
        }
        saveTodayExercises(context, fresh)
    }

    /** Drops one completed set on the last finished exercise. Returns that exercise index. */
    fun undoLastCompletedSet(context: Context): Int? {
        val today = loadOrCreateToday(context)
        if (today.exercises.isEmpty()) return null
        val exercises = today.exercises.toMutableList()
        val index = exercises.indexOfLast { it.sets > 0 && it.completedSets >= it.sets }
        if (index < 0) return null
        val exercise = exercises[index]
        exercises[index] = exercise.copy(
            completedSets = (exercise.completedSets - 1).coerceAtLeast(0),
            isTimerActive = false,
            remainingSeconds = 0,
            timerEndTime = 0
        )
        saveTodayExercises(context, exercises)
        return index
    }

    fun saveTodayExercises(context: Context, exercises: List<Exercise>) {
        val dateStr = todayDateString()
        val plannedJson = gson.toJson(exercises.map { it.copy(completedSets = 0) })
        val loggedJson = gson.toJson(exercises)
        AppDatabase.getDatabase(context).workoutLogDao().insert(
            WorkoutLogEntity(dateStr, plannedJson, loggedJson)
        )
    }

    /**
     * Replaces the routine template for [dayOfWeek] with [exercises] in this order.
     * Session progress stays out of the template. [dayOfWeek] is the makeup weekday
     * while a makeup session is active.
     */
    fun saveRoutineExercises(context: Context, dayOfWeek: Int, exercises: List<Exercise>) {
        val dao = AppDatabase.getDatabase(context).routineDao()
        val routine = dao.getByDayOfWeekSync(dayOfWeek) ?: return
        val template = exercises.map { exercise ->
            exercise.copy(
                completedSets = 0,
                isTimerActive = false,
                remainingSeconds = 0,
                timerEndTime = 0
            )
        }
        dao.insertSync(routine.copy(exercises = template))
    }
}