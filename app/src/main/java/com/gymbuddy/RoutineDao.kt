package com.gymbuddy

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routine_days ORDER BY dayOfWeek")
    fun getAllLive(): LiveData<List<RoutineDayEntity>>

    @Query("SELECT * FROM routine_days ORDER BY dayOfWeek")
    suspend fun getAll(): List<RoutineDayEntity>

    @Query("SELECT * FROM routine_days WHERE dayOfWeek = :dayOfWeek LIMIT 1")
    suspend fun getByDayOfWeek(dayOfWeek: Int): RoutineDayEntity?

    @Query("SELECT * FROM routine_days WHERE dayOfWeek = :dayOfWeek LIMIT 1")
    fun getByDayOfWeekSync(dayOfWeek: Int): RoutineDayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(vararg days: RoutineDayEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSync(day: RoutineDayEntity)

    @Query("DELETE FROM routine_days")
    suspend fun deleteAll()
}
