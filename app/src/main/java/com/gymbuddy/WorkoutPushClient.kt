package com.gymbuddy

import android.content.Context
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object WorkoutPushClient {
    private val gson = Gson()

    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    data class ExerciseSnapshot(
        val title: String,
        val weight: Int,
        val reps: Int,
        val sets: Int,
        val completedSets: Int,
        val rating: String,
        val notes: String
    )

    data class WorkoutPayload(
        val date: String,
        val isMakeup: Boolean,
        val dayOfWeek: Int,
        val startMs: Long,
        val endMs: Long,
        val durationMs: Long,
        val exerciseCount: Int,
        val setCount: Int,
        val partial: Boolean,
        val exercises: List<ExerciseSnapshot>
    )

    fun buildPayload(context: Context, summary: WorkoutClock.Summary): WorkoutPayload {
        val workout = WorkoutRepository.loadOrCreateToday(context)
        return WorkoutPayload(
            date = WorkoutRepository.dateString(summary.startMs),
            isMakeup = workout.isMakeup,
            dayOfWeek = workout.dayOfWeek,
            startMs = summary.startMs,
            endMs = summary.endMs,
            durationMs = summary.durationMs,
            exerciseCount = summary.exerciseCount,
            setCount = summary.setCount,
            partial = workout.exercises.any { it.sets > 0 && it.completedSets < it.sets },
            exercises = workout.exercises.map { exercise ->
                ExerciseSnapshot(
                    title = exercise.title,
                    weight = exercise.weight,
                    reps = exercise.reps,
                    sets = exercise.sets,
                    completedSets = exercise.completedSets,
                    rating = exercise.rating,
                    notes = exercise.notes
                )
            }
        )
    }

    fun push(context: Context, summary: WorkoutClock.Summary): Result<Unit> {
        val base = WorkerRemote.getUrl(context)
            ?: return Result.failure(IllegalStateException("No worker configured"))
        val body = gson.toJson(buildPayload(context, summary)).toRequestBody(jsonType)
        val request = Request.Builder()
            .url("$base/workouts")
            .post(body)
            .header("Content-Type", "application/json")
            .apply {
                val token = WorkerRemote.getToken(context)
                if (!token.isNullOrBlank()) {
                    header("Authorization", "Bearer $token")
                }
            }
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(IllegalStateException("Worker returned ${response.code}"))
            }
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}