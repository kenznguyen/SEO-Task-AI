package com.kenznguyen.seotaskai.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val instruction: String,
    val category: String,
    val status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis()
)
