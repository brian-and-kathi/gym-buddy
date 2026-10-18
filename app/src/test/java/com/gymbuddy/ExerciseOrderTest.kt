package com.gymbuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExerciseOrderTest {

    @Test
    fun startingTheFirstUnbegunExerciseDoesNotMove() {
        val exercises = mutableListOf(ex("A"), ex("B"), ex("C"))
        assertNull(ExerciseOrder.placeBegunExercise(exercises, fromIndex = 0, completedBefore = 0))
        assertEquals(listOf("A", "B", "C"), exercises.map { it.title })
    }

    @Test
    fun startingALaterExerciseInsertsItAtTheFirstUnbegunSlot() {
        val exercises = mutableListOf(
            ex("A"),
            ex("B"),
            ex("C", completed = 1),
            ex("D")
        )
        val movedTo = ExerciseOrder.placeBegunExercise(exercises, fromIndex = 2, completedBefore = 0)
        assertEquals(0, movedTo)
        assertEquals(listOf("C", "A", "B", "D"), exercises.map { it.title })
    }

    @Test
    fun startingTheNextSkippedExerciseInsertsAheadOfRemainingUnbegun() {
        val exercises = mutableListOf(
            ex("C", completed = 1),
            ex("A"),
            ex("B"),
            ex("D", completed = 1)
        )
        val movedTo = ExerciseOrder.placeBegunExercise(exercises, fromIndex = 3, completedBefore = 0)
        assertEquals(1, movedTo)
        assertEquals(listOf("C", "D", "A", "B"), exercises.map { it.title })
    }

    @Test
    fun aSecondSetOnAnAlreadyBegunExerciseDoesNotMove() {
        val exercises = mutableListOf(
            ex("C", completed = 2),
            ex("A"),
            ex("B")
        )
        assertNull(ExerciseOrder.placeBegunExercise(exercises, fromIndex = 0, completedBefore = 1))
        assertEquals(listOf("C", "A", "B"), exercises.map { it.title })
    }

    @Test
    fun remapCoversMovedCardShiftedCardsAndUntouchedCards() {
        val fromIndex = 3
        val toIndex = 1
        assertEquals(1, ExerciseOrder.remapIndex(3, fromIndex, toIndex))
        assertEquals(2, ExerciseOrder.remapIndex(1, fromIndex, toIndex))
        assertEquals(3, ExerciseOrder.remapIndex(2, fromIndex, toIndex))
        assertEquals(0, ExerciseOrder.remapIndex(0, fromIndex, toIndex))
        assertEquals(4, ExerciseOrder.remapIndex(4, fromIndex, toIndex))
    }

    private fun ex(title: String, completed: Int = 0) = Exercise(
        title = title,
        weight = 0,
        reps = 0,
        sets = 3,
        completedSets = completed
    )
}
