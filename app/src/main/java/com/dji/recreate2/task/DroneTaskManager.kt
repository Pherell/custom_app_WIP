package com.dji.recreate2.task

import android.content.Context
import android.util.Log
import com.dji.recreate2.aws.S3UploadManager
import com.dji.recreate2.sync.PostFlightS3Sync
import java.util.concurrent.ConcurrentLinkedQueue

data class DroneTask(
    val taskId: String,
    val taskName: String = taskId,
    val taskType: String = "ISR",
    val waypointsJson: String? = null,
    var status: String = "PENDING",
    val createdAt: Long = System.currentTimeMillis()
)

object DroneTaskManager {

    private const val TAG = "DroneTaskManager"

    @Volatile
    var activeTask: DroneTask? = null
        private set

    val taskQueue = ConcurrentLinkedQueue<DroneTask>()

    var onTaskStateChanged: ((DroneTask) -> Unit)? = null
    var onExecuteTaskCallback: ((DroneTask) -> Unit)? = null
    var onAbortTaskCallback: ((DroneTask) -> Unit)? = null

    val queuedCount: Int
        get() = taskQueue.size

    val activeTaskId: String?
        get() = activeTask?.taskId

    /**
     * Initializes and starts execution for [task].
     * Auto-creates local folder Pictures/[taskId] and Ceph S3 target subfolder .../captured/[taskId]/.
     */
    fun startTask(context: Context, task: DroneTask, executeNow: Boolean = true) {
        val cleanTaskId = task.taskId.trim().replace("/", "_").replace("\\", "_")
        if (cleanTaskId.isEmpty()) {
            Log.e(TAG, "Cannot start task: taskId is empty.")
            return
        }

        val updatedTask = task.copy(taskId = cleanTaskId, status = "RUNNING")
        activeTask = updatedTask

        Log.d(TAG, "Starting Task '${updatedTask.taskId}' (Type: ${updatedTask.taskType})")

        // 1. Auto-generate Local Storage Download Folder
        PostFlightS3Sync.saveLocalFetchFolderName(context, cleanTaskId)
        PostFlightS3Sync.getLocalFetchDirectory(context)

        // 2. Auto-generate Ceph S3 Remote Target Folder with .keep marker
        S3UploadManager.saveFolderConfig(context, S3UploadManager.FOLDER_MODE_CUSTOM, cleanTaskId)
        S3UploadManager.createRemoteFolder(
            context = context,
            folderName = cleanTaskId,
            onSuccess = {
                Log.d(TAG, "S3 folder marker created for task: $cleanTaskId")
            },
            onError = { err ->
                Log.w(TAG, "Failed to create S3 folder marker for task: $cleanTaskId ($err)")
            }
        )

        onTaskStateChanged?.invoke(updatedTask)

        if (executeNow) {
            onExecuteTaskCallback?.invoke(updatedTask)
        }
    }

    /**
     * Enqueues [task] into the task queue. If no task is currently active, starts it immediately.
     */
    fun enqueueTask(context: Context, task: DroneTask) {
        val cleanTaskId = task.taskId.trim().replace("/", "_").replace("\\", "_")
        val newTask = task.copy(taskId = cleanTaskId, status = "PENDING")

        taskQueue.add(newTask)
        Log.d(TAG, "Enqueued Task '${newTask.taskId}'. Total queued: ${taskQueue.size}")

        if (activeTask == null) {
            processNextTask(context)
        } else {
            onTaskStateChanged?.invoke(newTask)
        }
    }

    /**
     * Dequeues and executes the next pending task in queue.
     */
    fun processNextTask(context: Context): DroneTask? {
        val nextTask = taskQueue.poll()
        if (nextTask != null) {
            startTask(context, nextTask, executeNow = true)
        } else {
            activeTask = null
            Log.d(TAG, "No pending tasks in queue. Task engine is IDLE.")
        }
        return nextTask
    }

    /**
     * Cancels a task by [taskId]. If active, aborts execution and advances to the next task in queue.
     */
    fun cancelTask(context: Context, taskId: String): Boolean {
        val cleanId = taskId.trim()

        // 1. Check if it's the currently active running task
        if (activeTask?.taskId == cleanId) {
            val cancelledTask = activeTask?.copy(status = "CANCELLED")
            Log.w(TAG, "Cancelling active running task '$cleanId'...")

            if (cancelledTask != null) {
                onAbortTaskCallback?.invoke(cancelledTask)
                onTaskStateChanged?.invoke(cancelledTask)
            }

            activeTask = null
            processNextTask(context)
            return true
        }

        // 2. Otherwise search in pending taskQueue
        val iterator = taskQueue.iterator()
        while (iterator.hasNext()) {
            val task = iterator.next()
            if (task.taskId == cleanId) {
                iterator.remove()
                val cancelledTask = task.copy(status = "CANCELLED")
                Log.w(TAG, "Removed pending task '$cleanId' from queue.")
                onTaskStateChanged?.invoke(cancelledTask)
                return true
            }
        }

        Log.w(TAG, "Task '$cleanId' not found to cancel.")
        return false
    }

    /**
     * Emergency cancels all active and queued tasks, returning the engine to IDLE.
     */
    fun cancelAllTasks(context: Context) {
        Log.w(TAG, "Cancelling ALL active and queued tasks.")
        val runningTask = activeTask

        taskQueue.clear()
        activeTask = null

        if (runningTask != null) {
            val cancelledTask = runningTask.copy(status = "CANCELLED")
            onAbortTaskCallback?.invoke(cancelledTask)
            onTaskStateChanged?.invoke(cancelledTask)
        }
    }

    /**
     * Flushes pending task queue without interrupting the active running task.
     */
    fun clearQueue() {
        Log.d(TAG, "Clearing pending task queue. Size was: ${taskQueue.size}")
        taskQueue.clear()
    }

    /**
     * Marks the active task as COMPLETED and automatically processes the next task in queue.
     */
    fun onTaskCompleted(context: Context) {
        val current = activeTask
        if (current != null) {
            val completedTask = current.copy(status = "COMPLETED")
            Log.d(TAG, "Task '${current.taskId}' COMPLETED.")
            onTaskStateChanged?.invoke(completedTask)
        }
        activeTask = null
        processNextTask(context)
    }
}
