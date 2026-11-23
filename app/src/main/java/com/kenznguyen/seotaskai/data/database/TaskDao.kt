package com.kenznguyen.seotaskai.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskEntity): Long

    @Query("UPDATE tasks SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Delete
    suspend fun delete(task: TaskEntity)
}
