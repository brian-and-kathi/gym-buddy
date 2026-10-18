package com.gymbuddy

/**
 * Reorders a workout when an exercise is begun before earlier ones.
 * Beginning means the set that takes completed sets from zero to one.
 */
object ExerciseOrder {
    /**
     * Moves the exercise at [fromIndex] to the first not-yet-begun slot when
     * [completedBefore] is zero and an earlier exercise is still unbegun.
     * Returns the new index, or null when the order stays the same.
     * The list already includes the set that was just recorded.
     */
    fun placeBegunExercise(
        exercises: MutableList<Exercise>,
        fromIndex: Int,
        completedBefore: Int
    ): Int? {
        if (completedBefore != 0) return null
        if (fromIndex !in exercises.indices) return null
        if (exercises[fromIndex].completedSets <= 0) return null
        val insertAt = exercises.indexOfFirst { it.completedSets == 0 }
        if (insertAt < 0 || insertAt >= fromIndex) return null
        val moved = exercises.removeAt(fromIndex)
        exercises.add(insertAt, moved)
        return insertAt
    }

    /**
     * Maps a saved index after the exercise at [fromIndex] was inserted at [toIndex].
     * [toIndex] is before [fromIndex].
     */
    fun remapIndex(index: Int, fromIndex: Int, toIndex: Int): Int {
        if (toIndex >= fromIndex) return index
        return when {
            index == fromIndex -> toIndex
            index in toIndex until fromIndex -> index + 1
            else -> index
        }
    }
}
