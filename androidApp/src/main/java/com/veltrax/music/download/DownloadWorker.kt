package com.veltrax.music.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val url = inputData.getString(URL) ?: return@withContext Result.failure()
            val fileName = inputData.getString(NAME) ?: return@withContext Result.failure()
            val downloadId = inputData.getString(ID) ?: return@withContext Result.failure()
            val queue = TransferQueue.get(applicationContext)
            queue.insert(url, fileName, id = downloadId)
            queue.recovery()
            Result.success()
        }

    companion object {
        const val URL = "url"
        const val NAME = "name"
        const val ID = "downloadId"
        const val PROGRESS = "progress"
        const val BYTES = "bytes"
        const val TOTAL = "total"
    }
}
