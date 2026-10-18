package com.gymbuddy

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.gymbuddy.databinding.ActivityWorkoutSummaryBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class WorkoutSummaryActivity : AppCompatActivity(), DurationEditorDialogFragment.DurationEditorListener {

    private lateinit var binding: ActivityWorkoutSummaryBinding
    private var showingWords = false
    private var durationMs = 0L
    private var summaryData: WorkoutClock.Summary? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWorkoutSummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                cancelSummary()
            }
        })

        val startMs = intent.getLongExtra(WorkoutClock.EXTRA_START_MS, 0L)
        val endMs = intent.getLongExtra(WorkoutClock.EXTRA_END_MS, 0L)
        val summary = if (startMs > 0L && endMs >= startMs) {
            WorkoutClock.Summary(
                startMs = startMs,
                endMs = endMs,
                durationMs = endMs - startMs,
                exerciseCount = intent.getIntExtra(WorkoutClock.EXTRA_EXERCISE_COUNT, 0),
                setCount = intent.getIntExtra(WorkoutClock.EXTRA_SET_COUNT, 0)
            )
        } else {
            try {
                WorkoutClock.summary(this, WorkoutRepository.loadOrCreateToday(this).exercises)
            } catch (_: Exception) {
                null
            }
        }
        if (summary == null) {
            finish()
            return
        }

        summaryData = summary
        durationMs = summary.durationMs
        renderRange()
        binding.statsLine.text = getString(
            R.string.summary_stats,
            summary.exerciseCount,
            summary.setCount
        )
        renderDuration()

        binding.durationValue.setOnClickListener {
            showingWords = !showingWords
            renderDuration()
        }
        binding.durationValue.setOnLongClickListener {
            openDurationEditor()
            true
        }
        binding.editDurationLink.setOnClickListener { openDurationEditor() }
        binding.timeRange.setOnClickListener { openDurationEditor() }
        if (WorkerRemote.isConfigured(this)) {
            binding.pushButton.visibility = View.VISIBLE
            binding.pushButton.setOnClickListener { pushToWorker() }
            binding.statsLink.visibility = View.VISIBLE
            binding.statsLink.setOnClickListener { WorkerRemote.openDashboard(this) }
        } else {
            binding.pushButton.visibility = View.GONE
            binding.statsLink.visibility = View.GONE
        }
        binding.doneButton.setOnClickListener { close() }
        binding.cancelButton.setOnClickListener { cancelSummary() }
    }

    private fun openDurationEditor() {
        DurationEditorDialogFragment.newInstance(durationMs)
            .show(supportFragmentManager, "duration_editor")
    }

    override fun onDurationUpdated(durationMs: Long) {
        val summary = summaryData ?: return
        val endMs = summary.startMs + durationMs
        summaryData = summary.copy(endMs = endMs, durationMs = durationMs)
        this.durationMs = durationMs
        WorkoutClock.updateTimes(this, summary.startMs, endMs)
        renderRange()
        renderDuration()
        if (binding.pushButton.visibility == View.VISIBLE) {
            binding.pushButton.isEnabled = true
            binding.pushButton.setText(R.string.summary_push)
        }
    }

    private fun renderRange() {
        val summary = summaryData ?: return
        binding.timeRange.text = getString(
            R.string.summary_range,
            WorkoutClock.formatClock(summary.startMs),
            WorkoutClock.formatClock(summary.endMs)
        )
    }

    private fun renderDuration() {
        if (showingWords) {
            binding.durationValue.textSize = 28f
            binding.durationValue.typeface = android.graphics.Typeface.DEFAULT_BOLD
            binding.durationValue.text = WorkoutClock.formatDurationWords(durationMs)
            binding.durationHint.text = getString(R.string.summary_tap_time_digital)
        } else {
            binding.durationValue.textSize = 56f
            binding.durationValue.typeface = ResourcesCompat.getFont(this, R.font.seven_segment)
            binding.durationValue.text = WorkoutClock.formatDuration(durationMs)
            binding.durationHint.text = getString(R.string.summary_tap_time)
        }
    }

    private fun pushToWorker() {
        val summary = summaryData ?: return
        binding.pushButton.isEnabled = false
        binding.pushButton.setText(R.string.summary_pushing)
        thread {
            val result = WorkoutPushClient.push(this, summary)
            val routineError = if (result.isSuccess) {
                pushRoutine().exceptionOrNull()?.message
            } else {
                null
            }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (result.isSuccess && routineError == null) {
                    closeToAboutLog()
                } else {
                    binding.pushButton.isEnabled = true
                    binding.pushButton.setText(R.string.summary_push)
                    val message = if (result.isSuccess) {
                        routineError ?: getString(R.string.summary_push_routine_failed)
                    } else {
                        getString(R.string.summary_push_failed)
                    }
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun pushRoutine(): Result<Unit> {
        return try {
            val saved = runBlocking(Dispatchers.IO) {
                val days = RoutineExport.fromEntities(
                    AppDatabase.getDatabase(this@WorkoutSummaryActivity).routineDao().getAll()
                )
                RoutineCloudClient.save(this@WorkoutSummaryActivity, days)
            }
            saved.onSuccess { record ->
                WorkerRemote.markStandardAccepted(this, record.name, record.updatedAt)
            }
            saved.map { }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private fun close() {
        WorkoutClock.markPresented(this)
        finish()
    }

    private fun closeToAboutLog() {
        WorkoutClock.markPresented(this)
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(MainActivity.EXTRA_OPEN_ABOUT, true)
        })
        finish()
    }

    private fun cancelSummary() {
        val undo = intent.getBooleanExtra(WorkoutClock.EXTRA_UNDO_ON_CANCEL, false)
        thread {
            if (undo) {
                try {
                    WorkoutRepository.undoLastCompletedSet(this)
                } catch (_: Exception) {
                }
            }
            WorkoutClock.abandonSummary(this)
            WorkoutSync.notifyWorkoutChanged()
            try {
                ExerciseWidgetProvider.refreshAll(this)
            } catch (_: Exception) {
            }
            runOnUiThread {
                if (!isFinishing) finish()
            }
        }
    }
}