package com.gymbuddy

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.RemoteViews
import java.util.concurrent.Executors

class ExerciseWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        io.execute {
            bindAll(context)
            ExerciseWidgetTicker.startIfNeeded(context)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        io.execute { bindAll(context) }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        appWidgetIds.forEach { ExerciseWidgetState.clear(context, it) }
    }

    override fun onDisabled(context: Context) {
        ExerciseWidgetTicker.stop()
        cancelTimerEndAlarm(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_CLICK -> {
                val pendingResult = goAsync()
                io.execute {
                    try {
                        handleClick(context, intent)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            ACTION_TIMER_ENDED -> {
                io.execute { ExerciseWidgetTicker.start(context) }
            }
        }
    }

    companion object {
        const val ACTION_CLICK = "com.gymbuddy.widget.CLICK"
        const val ACTION_TIMER_ENDED = "com.gymbuddy.widget.TIMER_ENDED"
        const val EXTRA_CLICK_TYPE = "click_type"
        const val EXTRA_POSITION = "position"
        const val CLICK_COMPLETE_SET = 1
        const val CLICK_OPEN_APP = 2
        const val CLICK_PREV = 3
        const val CLICK_NEXT = 4
        const val CLICK_SHOW_TIP = 5
        const val CLICK_HIDE_TIP = 6
        private const val TIMER_RING_MAX = 1000

        internal val io = Executors.newSingleThreadExecutor()

        fun refreshAll(context: Context) {
            io.execute { bindAll(context) }
        }

        /** Keeps each widget on the same exercise after one exercise moves earlier in the list. */
        fun remapSavedIndexes(context: Context, fromIndex: Int, toIndex: Int) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ExerciseWidgetProvider::class.java))
            ids.forEach { id ->
                val current = ExerciseWidgetState.getIndex(context, id)
                if (current >= 0) {
                    ExerciseWidgetState.setIndex(
                        context,
                        id,
                        ExerciseOrder.remapIndex(current, fromIndex, toIndex)
                    )
                }
            }
            ExerciseWidgetPulse.remapIndex(fromIndex, toIndex)
        }

        internal fun bindAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ExerciseWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val workout = try {
                WorkoutRepository.loadOrCreateToday(context)
            } catch (_: Exception) {
                TodayWorkout(emptyList(), isRest = false, hasRoutine = false)
            }
            ids.forEach { id ->
                manager.updateAppWidget(id, buildRemoteViews(context, id, workout))
            }
        }

        internal fun tick(context: Context): Boolean {
            val workout = try {
                WorkoutRepository.loadOrCreateToday(context)
            } catch (_: Exception) {
                return false
            }
            val now = System.currentTimeMillis()
            val exercises = workout.exercises.toMutableList()
            var mutated = false
            var keepTicking = ExerciseWidgetPulse.isActive()

            exercises.forEachIndexed { index, exercise ->
                if (!exercise.isTimerActive) return@forEachIndexed
                if (now < exercise.timerEndTime) {
                    keepTicking = true
                    return@forEachIndexed
                }
                if (!ExerciseWidgetPulse.isTracking(index)) {
                    ExerciseWidgetPulse.start(index)
                    keepTicking = true
                }
                if (ExerciseWidgetPulse.isActive()) {
                    keepTicking = true
                } else {
                    exercises[index] = exercise.copy(
                        isTimerActive = false,
                        remainingSeconds = 0,
                        timerEndTime = 0,
                        timerDurationSeconds = 0
                    )
                    mutated = true
                    ExerciseWidgetPulse.clear()
                }
            }

            if (!keepTicking && ExerciseWidgetPulse.exerciseIndex >= 0 && !ExerciseWidgetPulse.isActive()) {
                ExerciseWidgetPulse.clear()
            }

            if (mutated) {
                WorkoutRepository.saveTodayExercises(context, exercises)
                WorkoutSync.notifyWorkoutChanged()
            }

            updateTimerRing(context, workout.copy(exercises = exercises))
            return keepTicking
        }

        private fun handleClick(context: Context, intent: Intent) {
            when (intent.getIntExtra(EXTRA_CLICK_TYPE, 0)) {
                CLICK_OPEN_APP -> {
                    try {
                        context.startActivity(openAppIntent(context))
                    } catch (_: Exception) {
                    }
                }
                CLICK_COMPLETE_SET -> {
                    val position = intent.getIntExtra(EXTRA_POSITION, -1)
                    completeSet(context, position)
                }
                CLICK_PREV, CLICK_NEXT -> {
                    val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                    val delta = if (intent.getIntExtra(EXTRA_CLICK_TYPE, 0) == CLICK_NEXT) 1 else -1
                    shiftExercise(context, widgetId, delta)
                }
                CLICK_SHOW_TIP, CLICK_HIDE_TIP -> {
                    val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                    if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
                    ExerciseWidgetState.setTipShown(
                        context,
                        widgetId,
                        intent.getIntExtra(EXTRA_CLICK_TYPE, 0) == CLICK_SHOW_TIP
                    )
                    val workout = WorkoutRepository.loadOrCreateToday(context)
                    AppWidgetManager.getInstance(context)
                        .updateAppWidget(widgetId, buildRemoteViews(context, widgetId, workout))
                }
            }
        }

        private fun shiftExercise(context: Context, appWidgetId: Int, delta: Int) {
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
            val workout = WorkoutRepository.loadOrCreateToday(context)
            if (workout.exercises.size <= 1) return
            val current = resolveIndex(context, appWidgetId, workout)
            val next = (current + delta).mod(workout.exercises.size)
            ExerciseWidgetState.setIndex(context, appWidgetId, next)
            AppWidgetManager.getInstance(context)
                .updateAppWidget(appWidgetId, buildRemoteViews(context, appWidgetId, workout))
        }

        private fun completeSet(context: Context, position: Int) {
            val workout = WorkoutRepository.loadOrCreateToday(context)
            if (position !in workout.exercises.indices) return

            val current = workout.exercises[position]
            val updated = applyPieTap(current)
            val exercises = workout.exercises.toMutableList()
            exercises[position] = updated
            val began = current.completedSets == 0 && updated.completedSets > 0
            val movedTo = if (began) {
                ExerciseOrder.placeBegunExercise(exercises, position, completedBefore = 0)
            } else {
                null
            }
            WorkoutRepository.saveTodayExercises(context, exercises)
            if (movedTo != null) {
                WorkoutRepository.saveRoutineExercises(context, workout.dayOfWeek, exercises)
            }

            val focus = movedTo ?: position
            val manager = AppWidgetManager.getInstance(context)
            manager.getAppWidgetIds(ComponentName(context, ExerciseWidgetProvider::class.java)).forEach { id ->
                ExerciseWidgetState.setIndex(context, id, focus)
            }
            if (movedTo != null && ExerciseWidgetPulse.isTracking(position)) {
                ExerciseWidgetPulse.remapIndex(position, movedTo)
            }

            if (updated.completedSets > current.completedSets && updated.completedSets < updated.sets) {
                vibrate(context)
            } else if (updated.completedSets > current.completedSets) {
                vibrate(context)
            }

            WorkoutSync.notifyWorkoutChanged()
            bindAll(context)
            val clockEvent = when {
                updated.completedSets > current.completedSets -> WorkoutClock.Event.SET_COMPLETE
                updated.completedSets < current.completedSets -> WorkoutClock.Event.SET_UNDONE
                else -> WorkoutClock.Event.LOAD
            }
            WorkoutClock.presentIfNeeded(context, exercises, clockEvent)

            if (updated.isTimerActive && updated.timerEndTime > System.currentTimeMillis()) {
                scheduleTimerEndAlarm(context, updated.timerEndTime)
                ExerciseWidgetTicker.start(context)
            } else {
                ExerciseWidgetPulse.clear()
                cancelTimerEndAlarm(context)
            }
        }

        fun applyPieTap(exercise: Exercise): Exercise {
            if (exercise.completedSets >= exercise.sets) {
                return exercise.copy(
                    completedSets = 0,
                    isTimerActive = false,
                    remainingSeconds = 0,
                    timerEndTime = 0,
                    timerDurationSeconds = 0
                )
            }
            val completed = exercise.completedSets + 1
            if (completed >= exercise.sets) {
                return exercise.copy(
                    completedSets = completed,
                    isTimerActive = false,
                    remainingSeconds = 0,
                    timerEndTime = 0,
                    timerDurationSeconds = 0
                )
            }
            val cooldown = exercise.cooldownSeconds()
            return exercise.copy(
                completedSets = completed,
                isTimerActive = true,
                remainingSeconds = cooldown,
                timerDurationSeconds = cooldown,
                timerEndTime = System.currentTimeMillis() + cooldown * 1000L
            )
        }

        fun buildRemoteViews(context: Context, appWidgetId: Int, workout: TodayWorkout): RemoteViews {
            if (!workout.hasRoutine || workout.isRest || workout.exercises.isEmpty()) {
                val views = RemoteViews(context.packageName, R.layout.widget_exercise_empty)
                val message = when {
                    workout.isRest && workout.isMakeup -> context.getString(R.string.widget_empty_makeup_rest)
                    workout.isRest -> context.getString(R.string.widget_empty_rest)
                    !workout.hasRoutine -> context.getString(R.string.widget_empty_none)
                    else -> context.getString(R.string.widget_empty_none)
                }
                views.setTextViewText(R.id.empty_message, message)
                views.setOnClickPendingIntent(
                    R.id.empty_root,
                    PendingIntent.getActivity(
                        context,
                        appWidgetId,
                        openAppIntent(context),
                        pendingFlags(immutable = true)
                    )
                )
                return views
            }

            val displayIndex = resolveIndex(context, appWidgetId, workout)
            ExerciseWidgetState.setIndex(context, appWidgetId, displayIndex)
            val exercise = workout.exercises[displayIndex]
            val views = RemoteViews(context.packageName, R.layout.widget_exercise)

            if (workout.isMakeup) {
                views.setViewVisibility(R.id.session_banner, android.view.View.VISIBLE)
                views.setTextViewText(
                    R.id.session_banner,
                    context.getString(R.string.widget_makeup_banner, fullDayName(workout.dayOfWeek))
                )
            } else {
                views.setViewVisibility(R.id.session_banner, android.view.View.GONE)
            }

            views.setTextViewText(R.id.title_text, exercise.title)
            views.setTextViewText(
                R.id.weight_value,
                if (exercise.weight == 0) "-" else exercise.weight.toString()
            )
            views.setTextViewText(R.id.reps_value, exercise.reps.toString())
            views.setTextViewText(R.id.sets_value, exercise.sets.toString())

            val density = context.resources.displayMetrics.density
            val sizePx = kotlin.math.min(360, (160f * density).toInt()).coerceAtLeast(160)
            val bitmap = ProgressPieRenderer.createWidgetBitmap(
                sizePx = sizePx,
                completedSets = exercise.completedSets,
                totalSets = exercise.sets
            )
            views.setImageViewBitmap(R.id.pie_chart, bitmap)
            views.setContentDescription(
                R.id.pie_chart,
                context.getString(R.string.widget_pie_description, exercise.completedSets, exercise.sets)
            )
            applyTimerRing(views, exercise, displayIndex, System.currentTimeMillis(), ExerciseWidgetState.isTipShown(context, appWidgetId))

            val canPage = workout.exercises.size > 1
            views.setViewVisibility(R.id.btn_prev, if (canPage) android.view.View.VISIBLE else android.view.View.GONE)
            views.setViewVisibility(R.id.btn_next, if (canPage) android.view.View.VISIBLE else android.view.View.GONE)

            applyTipMode(context, views, appWidgetId, exercise)
            views.setOnClickPendingIntent(
                R.id.stats_area,
                openAppPendingIntent(context, appWidgetId, displayIndex)
            )
            views.setOnClickPendingIntent(
                R.id.title_text,
                clickPendingIntent(context, appWidgetId, CLICK_SHOW_TIP, displayIndex)
            )
            views.setOnClickPendingIntent(
                R.id.tip_text,
                clickPendingIntent(context, appWidgetId, CLICK_HIDE_TIP, displayIndex)
            )
            val completeSetIntent = clickPendingIntent(context, appWidgetId, CLICK_COMPLETE_SET, displayIndex)
            views.setOnClickPendingIntent(R.id.pie_chart, completeSetIntent)
            views.setOnClickPendingIntent(R.id.timer_ring, completeSetIntent)
            if (canPage) {
                views.setOnClickPendingIntent(
                    R.id.btn_prev,
                    clickPendingIntent(context, appWidgetId, CLICK_PREV, displayIndex)
                )
                views.setOnClickPendingIntent(
                    R.id.btn_next,
                    clickPendingIntent(context, appWidgetId, CLICK_NEXT, displayIndex)
                )
            }
            return views
        }

        private fun resolveIndex(context: Context, appWidgetId: Int, workout: TodayWorkout): Int {
            val saved = ExerciseWidgetState.getIndex(context, appWidgetId)
            if (saved in workout.exercises.indices) return saved
            val firstIncomplete = workout.exercises.indexOfFirst { it.completedSets < it.sets }
            return if (firstIncomplete >= 0) firstIncomplete else 0
        }

        private fun updateTimerRing(context: Context, workout: TodayWorkout) {
            if (workout.exercises.isEmpty()) return
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ExerciseWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val now = System.currentTimeMillis()
            ids.forEach { id ->
                val index = resolveIndex(context, id, workout)
                val views = RemoteViews(context.packageName, R.layout.widget_exercise)
                applyTimerRing(
                    views,
                    workout.exercises[index],
                    index,
                    now,
                    ExerciseWidgetState.isTipShown(context, id)
                )
                manager.partiallyUpdateAppWidget(id, views)
            }
        }

        private fun applyTipMode(context: Context, views: RemoteViews, appWidgetId: Int, exercise: Exercise) {
            val showingTip = ExerciseWidgetState.isTipShown(context, appWidgetId)
            if (showingTip) {
                val notes = exercise.notes.removePrefix("Notes: ").trim()
                views.setTextViewText(
                    R.id.tip_text,
                    notes.ifBlank { context.getString(R.string.widget_no_tip) }
                )
                views.setViewVisibility(R.id.normal_content, android.view.View.GONE)
                views.setViewVisibility(R.id.tip_text, android.view.View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.normal_content, android.view.View.VISIBLE)
                views.setViewVisibility(R.id.tip_text, android.view.View.GONE)
            }
        }

        private fun applyTimerRing(
            views: RemoteViews,
            exercise: Exercise,
            position: Int,
            now: Long,
            tipShown: Boolean = false
        ) {
            if (tipShown) {
                views.setViewVisibility(R.id.timer_ring, android.view.View.GONE)
                return
            }
            val pulseOn = ExerciseWidgetPulse.isOn(position)
            val pulseActive = ExerciseWidgetPulse.isTracking(position) && ExerciseWidgetPulse.isActive()
            when {
                pulseOn -> {
                    views.setViewVisibility(R.id.timer_ring, android.view.View.VISIBLE)
                    views.setProgressBar(R.id.timer_ring, TIMER_RING_MAX, TIMER_RING_MAX, false)
                }
                pulseActive -> {
                    views.setViewVisibility(R.id.timer_ring, android.view.View.INVISIBLE)
                    views.setProgressBar(R.id.timer_ring, TIMER_RING_MAX, 0, false)
                }
                exercise.isTimerActive && exercise.timerEndTime > now -> {
                    val durationMs = (
                        if (exercise.timerDurationSeconds > 0) exercise.timerDurationSeconds
                        else exercise.cooldownSeconds()
                        ).coerceAtLeast(1) * 1000L
                    val remaining = (exercise.timerEndTime - now).coerceAtLeast(0L)
                    val progress = ((remaining * TIMER_RING_MAX) / durationMs).toInt().coerceIn(0, TIMER_RING_MAX)
                    views.setViewVisibility(R.id.timer_ring, android.view.View.VISIBLE)
                    views.setProgressBar(R.id.timer_ring, TIMER_RING_MAX, progress, false)
                }
                else -> {
                    views.setViewVisibility(R.id.timer_ring, android.view.View.GONE)
                }
            }
        }

        private fun clickPendingIntent(
            context: Context,
            appWidgetId: Int,
            clickType: Int,
            position: Int
        ): PendingIntent {
            val intent = Intent(context, ExerciseWidgetProvider::class.java).apply {
                action = ACTION_CLICK
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(EXTRA_CLICK_TYPE, clickType)
                putExtra(EXTRA_POSITION, position)
            }
            val requestCode = appWidgetId * 10 + clickType
            return PendingIntent.getBroadcast(context, requestCode, intent, pendingFlags(immutable = true))
        }

        private fun fullDayName(dayOfWeek: Int): String {
            val days = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
            return days.getOrElse(dayOfWeek - 1) { "" }
        }

        fun openAppIntent(context: Context, exerciseIndex: Int = -1): Intent {
            return Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_WORKOUT, true)
                if (exerciseIndex >= 0) {
                    putExtra(MainActivity.EXTRA_EXERCISE_INDEX, exerciseIndex)
                }
            }
        }

        private fun openAppPendingIntent(context: Context, appWidgetId: Int, exerciseIndex: Int): PendingIntent {
            return PendingIntent.getActivity(
                context,
                appWidgetId * 10 + CLICK_OPEN_APP,
                openAppIntent(context, exerciseIndex),
                pendingFlags(immutable = true)
            )
        }

        fun pendingFlags(immutable: Boolean): Int {
            val mutability = if (immutable) PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_MUTABLE
            return PendingIntent.FLAG_UPDATE_CURRENT or mutability
        }

        private fun vibrate(context: Context) {
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(50)
            }
        }

        private fun scheduleTimerEndAlarm(context: Context, endTime: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pending = timerEndPendingIntent(context)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTime, pending)
                    } else {
                        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTime, pending)
                    }
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTime, pending)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, endTime, pending)
                }
            } catch (_: Exception) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, endTime, pending)
            }
        }

        private fun cancelTimerEndAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(timerEndPendingIntent(context))
        }

        private fun timerEndPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, ExerciseWidgetProvider::class.java).apply {
                action = ACTION_TIMER_ENDED
            }
            return PendingIntent.getBroadcast(context, 0, intent, pendingFlags(immutable = true))
        }
    }
}

object ExerciseWidgetState {
    private const val PREFS = "exercise_widget_state"
    private fun key(appWidgetId: Int) = "index_$appWidgetId"
    private fun tipKey(appWidgetId: Int) = "tip_$appWidgetId"

    fun getIndex(context: Context, appWidgetId: Int): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key(appWidgetId), -1)
    }

    fun setIndex(context: Context, appWidgetId: Int, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(key(appWidgetId), index)
            .apply()
    }

    fun isTipShown(context: Context, appWidgetId: Int): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(tipKey(appWidgetId), false)
    }

    fun setTipShown(context: Context, appWidgetId: Int, shown: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(tipKey(appWidgetId), shown)
            .apply()
    }

    fun clear(context: Context, appWidgetId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(key(appWidgetId))
            .remove(tipKey(appWidgetId))
            .apply()
    }
}

object ExerciseWidgetPulse {
    const val PULSE_DURATION_MS = 1200L
    const val PULSE_STEP_MS = 200L

    @Volatile
    var exerciseIndex: Int = -1
        private set

    @Volatile
    var startTime: Long = 0L
        private set

    fun start(index: Int) {
        exerciseIndex = index
        startTime = SystemClock.elapsedRealtime()
    }

    fun clear() {
        exerciseIndex = -1
        startTime = 0L
    }

    fun remapIndex(fromIndex: Int, toIndex: Int) {
        if (exerciseIndex < 0) return
        exerciseIndex = ExerciseOrder.remapIndex(exerciseIndex, fromIndex, toIndex)
    }

    fun isTracking(index: Int): Boolean = exerciseIndex == index && startTime > 0L

    fun isActive(): Boolean {
        if (exerciseIndex < 0 || startTime <= 0L) return false
        return SystemClock.elapsedRealtime() - startTime < PULSE_DURATION_MS
    }

    fun isOn(index: Int): Boolean {
        if (!isTracking(index) || !isActive()) return false
        val elapsed = SystemClock.elapsedRealtime() - startTime
        return (elapsed / PULSE_STEP_MS) % 2L == 0L
    }
}

object ExerciseWidgetTicker {
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    @Volatile private var running = false
    @Volatile private var generation = 0
    private var runnable: Runnable? = null

    fun startIfNeeded(context: Context) {
        val workout = try {
            WorkoutRepository.loadOrCreateToday(context)
        } catch (_: Exception) {
            return
        }
        val now = System.currentTimeMillis()
        val needsTick = workout.exercises.any { it.isTimerActive && it.timerEndTime > 0 } ||
            ExerciseWidgetPulse.isActive()
        if (needsTick) {
            if (workout.exercises.any { it.isTimerActive && now >= it.timerEndTime && it.timerEndTime > 0 }) {
                val index = workout.exercises.indexOfFirst { it.isTimerActive && now >= it.timerEndTime }
                if (index >= 0 && !ExerciseWidgetPulse.isTracking(index)) {
                    ExerciseWidgetPulse.start(index)
                }
            }
            start(context)
        }
    }

    fun start(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            generation++
            val gen = generation
            runnable?.let { handler.removeCallbacks(it) }
            running = true
            val next = object : Runnable {
                override fun run() {
                    ExerciseWidgetProvider.io.execute {
                        val keepGoing = try {
                            ExerciseWidgetProvider.tick(app)
                        } catch (_: Exception) {
                            false
                        }
                        synchronized(lock) {
                            if (gen != generation) return@execute
                            if (keepGoing) {
                                handler.postDelayed(this, ExerciseWidgetPulse.PULSE_STEP_MS)
                            } else {
                                running = false
                            }
                        }
                    }
                }
            }
            runnable = next
            handler.post(next)
        }
    }

    fun stop() {
        synchronized(lock) {
            generation++
            runnable?.let { handler.removeCallbacks(it) }
            runnable = null
            running = false
        }
    }
}