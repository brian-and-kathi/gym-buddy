package com.gymbuddy

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.gymbuddy.databinding.FragmentWorkoutBinding
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.max

class WorkoutFragment : Fragment() {

    private var _binding: FragmentWorkoutBinding? = null
    private val binding get() = _binding!!
    private val exercises = mutableListOf<Exercise>()
    private val smallPies = mutableListOf<ProgressPieChart>()
    private val gson = Gson()
    private var makeupDayOfWeek: Int? = null
    private var isMakeup = false
    private var currentSwelledPosition = -1
    private var loadToken = 0
    private var seenGeneration = -1
    private var pageCallbackRegistered = false
    /** Index of an exercise whose first set was just logged, until that begin is committed. */
    private var pendingBeginIndex: Int? = null
    /** While true, card updates must not write a single routine slot. The order write is in flight. */
    private var suppressRoutinePersist = false
    private val reloadFromWidget = Runnable {
        if (isAdded && _binding != null) {
            loadWorkout(preservePage = true)
        }
    }
    private val onExternalWorkoutChange: () -> Unit = {
        seenGeneration = WorkoutSync.generation()
        view?.removeCallbacks(reloadFromWidget)
        view?.postDelayed(reloadFromWidget, 250)
    }

    companion object {
        private const val ARG_MAKEUP_DAY = "makeup_day"
        private const val ARG_INITIAL_PAGE = "initial_page"

        fun newInstance(makeupDayOfWeek: Int): WorkoutFragment {
            val fragment = WorkoutFragment()
            val args = Bundle()
            args.putInt(ARG_MAKEUP_DAY, makeupDayOfWeek)
            fragment.arguments = args
            return fragment
        }

        fun withInitialPage(page: Int): WorkoutFragment {
            val fragment = WorkoutFragment()
            val args = Bundle()
            args.putInt(ARG_INITIAL_PAGE, page)
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            val day = it.getInt(ARG_MAKEUP_DAY, -1)
            if (day != -1) {
                if (day == WorkoutRepository.todayDayOfWeek()) {
                    MakeupSession.clear(requireContext())
                } else {
                    MakeupSession.set(requireContext(), day)
                }
            }
        }
        restoreMakeupFromSession()
    }

    private fun restoreMakeupFromSession() {
        val sessionDay = MakeupSession.getDayOfWeek(requireContext())
        makeupDayOfWeek = sessionDay
        isMakeup = sessionDay != null
    }

    private fun getDayName(dayOfWeek: Int): String {
        val days = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        return days[dayOfWeek - 1]
    }

    // ── Circle appearance constants ──
    private val CIRCLE_SIZE_DP = 42f          // Full size of each pie when not crowded
    private val OVERLAP_DP = 12f              // How much each circle overlaps the previous one
                                          // 0 = no overlap, 36 = fully stacked, 18 = ~50% overlap

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentWorkoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        restoreMakeupFromSession()
        applyHeader()
        binding.makeupLink.setOnClickListener {
            lifecycleScope.launch {
                showMakeupDayDialog()
            }
        }
        binding.endWorkoutLink.setOnClickListener { confirmEndWorkout() }
        loadWorkout()
    }

    private fun confirmEndWorkout() {
        if (!isAdded || exercises.isEmpty()) return
        val unfinished = exercises.any { it.sets > 0 && it.completedSets < it.sets }
        if (!unfinished) {
            openEndSummary()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.end_workout_title)
            .setMessage(R.string.end_workout_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.end_workout) { _, _ ->
                openEndSummary()
            }
            .show()
    }

    private fun openEndSummary() {
        if (!isAdded) return
        val summary = WorkoutClock.endNow(requireContext(), exercises) ?: return
        WorkoutClock.launchSummary(requireContext(), summary, undoOnCancel = false)
    }

    private fun refreshEndWorkoutLink() {
        if (_binding == null) return
        val allDone = exercises.isNotEmpty() && exercises.all { it.sets <= 0 || it.completedSets >= it.sets }
        val show = exercises.isNotEmpty() && (!allDone || !WorkoutClock.autoSummaryEnabled(requireContext()))
        binding.endWorkoutLink.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun applyHeader() {
        if (_binding == null) return
        if (isMakeup && makeupDayOfWeek != null) {
            binding.dateText.text = "Makeup: ${getDayName(makeupDayOfWeek!!)}"
        } else {
            val dateFormat = SimpleDateFormat("EEE MMM d, yyyy", Locale.getDefault())
            binding.dateText.text = dateFormat.format(Date())
        }
    }

    override fun onStart() {
        super.onStart()
        WorkoutSync.addListener(onExternalWorkoutChange)
        val generation = WorkoutSync.generation()
        if (seenGeneration != -1 && generation != seenGeneration) {
            loadWorkout(preservePage = true)
        }
        seenGeneration = generation
    }

    override fun onStop() {
        view?.removeCallbacks(reloadFromWidget)
        WorkoutSync.removeListener(onExternalWorkoutChange)
        super.onStop()
    }

    private fun loadWorkout(preservePage: Boolean = false) {
        val pageToRestore = if (preservePage && _binding != null) binding.viewPager.currentItem else -1
        val requestedPage = if (!preservePage) arguments?.getInt(ARG_INITIAL_PAGE, -1) ?: -1 else -1
        val token = ++loadToken
        lifecycleScope.launch {
            if (token != loadToken) return@launch
            val workout = withContext(Dispatchers.IO) {
                WorkoutRepository.loadOrCreateToday(requireContext())
            }
            if (token != loadToken || _binding == null) return@launch

            makeupDayOfWeek = if (workout.isMakeup) workout.dayOfWeek else null
            isMakeup = workout.isMakeup
            applyHeader()

            if (workout.isRest) {
                exercises.clear()
                refreshEndWorkoutLink()
                Toast.makeText(requireContext(), "Rest Day!", Toast.LENGTH_SHORT).show()
                binding.viewPager.adapter = null
            } else if (workout.hasRoutine) {
                exercises.clear()
                exercises.addAll(workout.exercises)

                if (token != loadToken || _binding == null) return@launch

                val firstIncompleteIndex = exercises.indexOfFirst { it.completedSets < it.sets }
                val targetPage = when {
                    requestedPage in exercises.indices -> requestedPage
                    pageToRestore in exercises.indices -> pageToRestore
                    firstIncompleteIndex != -1 -> firstIncompleteIndex
                    else -> 0
                }
                presentExercises(targetPage)

                WorkoutClock.presentIfNeeded(requireActivity(), exercises, WorkoutClock.Event.LOAD)
                refreshEndWorkoutLink()
            } else {
                exercises.clear()
                refreshEndWorkoutLink()
                Toast.makeText(requireContext(), "No routine for today", Toast.LENGTH_SHORT).show()
                binding.viewPager.adapter = null
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pageCallbackRegistered = false
        saveWorkoutLog()
        _binding = null
    }

    private fun onSetCompleted(@Suppress("UNUSED_PARAMETER") position: Int) {
        saveWorkoutLog()
        if (isAdded) {
            ExerciseWidgetProvider.refreshAll(requireContext())
        }
    }

    private fun onExerciseUpdated(
        position: Int,
        updatedExercise: Exercise,
        oldCompleted: Int,
        newCompleted: Int
    ) {
        // A card created before a reorder still reports its old index. The exercise
        // object already lives at its new index, so ignore that stale write.
        val liveIndex = exercises.indexOfFirst { it === updatedExercise }
        if (liveIndex >= 0 && liveIndex != position) return

        if (position in exercises.indices) {
            exercises[position] = updatedExercise
            if (position in smallPies.indices) {
                smallPies[position].setProgress(updatedExercise.completedSets, updatedExercise.sets)
            }
        }
        val beginning = oldCompleted == 0 && newCompleted > 0 && position in exercises.indices
        if (beginning && pendingBeginIndex == null) {
            pendingBeginIndex = position
            saveWorkoutLog()
            // Let this tap finish the cooldown timer on the current card, then move it.
            val host = view
            if (host != null) {
                host.post { commitBegunExercise() }
            } else {
                commitBegunExercise()
            }
        } else if (suppressRoutinePersist || pendingBeginIndex == position) {
            saveWorkoutLog()
        } else {
            saveWorkoutLog()
            persistExerciseToRoutine(position, updatedExercise)
        }
        if (oldCompleted != newCompleted || !updatedExercise.isTimerActive) {
            if (isAdded) {
                ExerciseWidgetProvider.refreshAll(requireContext())
            }
        }
        if (oldCompleted != newCompleted && isAdded) {
            val event = if (newCompleted > oldCompleted) {
                WorkoutClock.Event.SET_COMPLETE
            } else {
                WorkoutClock.Event.SET_UNDONE
            }
            WorkoutClock.presentIfNeeded(requireActivity(), exercises, event)
        }
        refreshEndWorkoutLink()
    }

    private fun commitBegunExercise() {
        val from = pendingBeginIndex ?: return
        if (!isAdded || _binding == null || from !in exercises.indices) {
            pendingBeginIndex = null
            return
        }
        val movedTo = ExerciseOrder.placeBegunExercise(exercises, from, completedBefore = 0)
        pendingBeginIndex = null
        if (movedTo == null) {
            persistExerciseToRoutine(from, exercises[from])
            return
        }
        // Drop an in-flight reload so it cannot paint the old order after this save.
        loadToken++
        suppressRoutinePersist = true
        try {
            val snapshot = exercises.toList()
            val day = makeupDayOfWeek ?: Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            val appContext = requireContext().applicationContext
            runBlocking(Dispatchers.IO) {
                WorkoutRepository.saveTodayExercises(appContext, snapshot)
                WorkoutRepository.saveRoutineExercises(appContext, day, snapshot)
            }
            if (!isAdded || _binding == null) return
            ExerciseWidgetProvider.remapSavedIndexes(requireContext(), from, movedTo)
            presentExercises(movedTo)
            ExerciseWidgetProvider.refreshAll(appContext)
        } finally {
            suppressRoutinePersist = false
        }
    }

    private fun presentExercises(targetPage: Int) {
        if (_binding == null) return
        binding.viewPager.adapter = ExercisePagerAdapter(
            this,
            exercises,
            this::onSetCompleted,
            this::onExerciseUpdated
        )
        binding.viewPager.setPageTransformer(PageFlipPageTransformer())
        if (!pageCallbackRegistered) {
            pageCallbackRegistered = true
            binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    updateCircleHighlights(position)
                }
            })
        }

        binding.circlesContainer.removeAllViews()
        smallPies.clear()
        currentSwelledPosition = -1

        if (exercises.isNotEmpty()) {
            val density = resources.displayMetrics.density
            val circleSizePx = (CIRCLE_SIZE_DP * density).toInt()
            val negativeMarginPx = (-OVERLAP_DP * density).toInt()

            exercises.forEachIndexed { index, exercise ->
                val pie = ProgressPieChart(requireContext()).apply {
                    val params = LinearLayout.LayoutParams(
                        circleSizePx,
                        circleSizePx
                    ).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        if (index > 0) {
                            marginStart = negativeMarginPx
                        }
                    }
                    layoutParams = params
                    setProgress(exercise.completedSets, exercise.sets)
                    setSegmented(true)
                    elevation = index * 2f
                    setOnClickListener {
                        if (_binding != null && index in exercises.indices) {
                            binding.viewPager.setCurrentItem(index, true)
                        }
                    }
                }
                smallPies.add(pie)
                binding.circlesContainer.addView(pie)
            }
        }

        if (exercises.isNotEmpty()) {
            val page = targetPage.coerceIn(exercises.indices)
            binding.viewPager.setCurrentItem(page, false)
            updateCircleHighlights(binding.viewPager.currentItem)
        }
    }

    private fun saveWorkoutLog() {
        if (exercises.isNotEmpty() && isAdded) { // Check if fragment is attached
            val context = requireContext()
            CoroutineScope(Dispatchers.IO).launch {
                val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                val plannedJson = gson.toJson(exercises.map { it.copy(completedSets = 0) }) // planned without completion
                val loggedJson = gson.toJson(exercises)
                val log = WorkoutLogEntity(dateStr, plannedJson, loggedJson)
                AppDatabase.getDatabase(context).workoutLogDao().insert(log)
            }
        }
    }

    /**
     * Writes exercise edits (title, weight, reps, sets, notes, rating, etc.) back to the
     * routine day being performed — today's day, or the makeup day when applicable.
     * Matches by list index so title renames still update the correct exercise.
     */
    private fun persistExerciseToRoutine(position: Int, updatedExercise: Exercise) {
        if (!isAdded || context == null) return
        lifecycleScope.launch {
            val targetDayOfWeek = makeupDayOfWeek ?: Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
            val routineDay = withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(requireContext()).routineDao().getByDayOfWeek(targetDayOfWeek)
            } ?: return@launch

            val updatedExercises = routineDay.exercises.toMutableList()
            if (position !in updatedExercises.indices) return@launch

            // Keep session progress out of the reusable routine template
            updatedExercises[position] = updatedExercise.copy(
                completedSets = 0,
                isTimerActive = false,
                remainingSeconds = 0,
                timerEndTime = 0
            )
            val updatedRoutineDay = routineDay.copy(exercises = updatedExercises)
            withContext(Dispatchers.IO) {
                AppDatabase.getDatabase(requireContext()).routineDao().insertAll(updatedRoutineDay)
            }
        }
    }

    private class ExercisePagerAdapter(
        fragment: Fragment,
        private val exercises: List<Exercise>,
        private val onSetCompleted: (Int) -> Unit,
        private val onUpdate: (Int, Exercise, Int, Int) -> Unit
    ) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = exercises.size
        override fun createFragment(position: Int): Fragment {
            val fragment = ExerciseWorkoutFragment.newInstance(exercises[position], position)
            fragment.setOnSetCompletedListener(onSetCompleted)
            fragment.setOnUpdateListener(onUpdate)
            return fragment
        }
    }

    private fun updateCircleHighlights(currentPosition: Int) {
        // Restore previously swelled pie if different from current
        if (currentSwelledPosition != -1 && currentSwelledPosition != currentPosition) {
            smallPies[currentSwelledPosition].startRestoreAnimation()
        }

        // Swell the current pie if not already swelled
        if (currentPosition != currentSwelledPosition) {
            smallPies[currentPosition].startSwellAnimation()
            currentSwelledPosition = currentPosition
        }

        // Update highlighting
        for (i in smallPies.indices) {
            smallPies[i].setHighlighted(i == currentPosition)
        }
    }

    private class PageFlipPageTransformer : ViewPager2.PageTransformer {
        override fun transformPage(page: View, position: Float) {
            val rotation = -30f * position
            page.rotationY = rotation
            page.alpha = 1f - Math.abs(position) * 0.5f
        }
    }

    private suspend fun showMakeupDayDialog() {
        val allRoutineDays = withContext(Dispatchers.IO) {
            AppDatabase.getDatabase(requireContext()).routineDao().getAll()
        }
        val dayNames = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        val availableDays = allRoutineDays.map {
            val baseName = dayNames[it.dayOfWeek - 1]
            if (it.isRest) "$baseName (Rest)" else baseName
        }.toTypedArray()
        val availableDayOfWeeks = allRoutineDays.map { it.dayOfWeek }.toIntArray()
        val isRestDay = allRoutineDays.map { it.isRest }.toBooleanArray()

        if (availableDays.isEmpty()) {
            Toast.makeText(requireContext(), "No days available for makeup", Toast.LENGTH_SHORT).show()
            return
        }

        val listView = ListView(requireContext())
        val adapter = MakeupDayAdapter(requireContext(), availableDays, isRestDay)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            if (!isRestDay[position]) {
                adapter.setSelectedPosition(position)
            }
        }

        val today = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val currentDay = makeupDayOfWeek ?: today
        val preselectIndex = availableDayOfWeeks.indexOf(currentDay)
        if (preselectIndex >= 0 && !isRestDay[preselectIndex]) {
            adapter.setSelectedPosition(preselectIndex)
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Select Makeup Day")
            .setView(listView)
            .setPositiveButton("OK") { _, _ ->
                val selectedIndex = adapter.getSelectedPosition()
                if (selectedIndex != -1) {
                    val selectedDayOfWeek = availableDayOfWeeks[selectedIndex]
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            WorkoutRepository.switchActiveDay(requireContext(), selectedDayOfWeek)
                        }
                        if (!isAdded) return@launch
                        ExerciseWidgetProvider.refreshAll(requireContext())
                        (requireActivity() as MainActivity).replaceFragment(WorkoutFragment())
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
