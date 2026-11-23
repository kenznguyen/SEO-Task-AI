package com.kenznguyen.seotaskai.data.repository

import com.kenznguyen.seotaskai.data.database.TaskDao
import com.kenznguyen.seotaskai.data.database.TaskEntity
import kotlinx.coroutines.flow.Flow

class TaskRepository(private val dao: TaskDao) {
    fun observeTasks(): Flow<List<TaskEntity>> = dao.observeAll()
    suspend fun addTask(title: String, instruction: String, category: String) {
        dao.insert(TaskEntity(title = title, instruction = instruction, category = category))
    }
    suspend fun updateStatus(id: Long, status: String) = dao.updateStatus(id, status)
    suspend fun deleteTask(task: TaskEntity) = dao.delete(task)
}
