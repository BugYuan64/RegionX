package com.regionx.app.data

import android.content.Context
import com.regionx.app.core.model.TaskRecord
import com.regionx.app.core.model.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 已打开的完成版本；任务正文与读状态分开保存，历史任务默认未读。 */
class TaskReadStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("task_reads", Context.MODE_PRIVATE)
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    @Synchronized
    fun isUnread(task: TaskRecord): Boolean =
        task.status == TaskStatus.Completed &&
            (!preferences.contains(task.id) || task.updatedAt > preferences.getLong(task.id, Long.MIN_VALUE))

    /** 活跃任务尚无完成结果，打开它不会提前消费即将到达的未读结果。 */
    @Synchronized
    fun markRead(task: TaskRecord) {
        if (task.status != TaskStatus.Completed) return
        if (preferences.contains(task.id) && preferences.getLong(task.id, Long.MIN_VALUE) >= task.updatedAt) return
        preferences.edit().putLong(task.id, task.updatedAt).apply()
        _revision.value += 1L
    }
}
