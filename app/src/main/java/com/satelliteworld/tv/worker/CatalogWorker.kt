package com.satelliteworld.tv.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.satelliteworld.tv.data.PlaylistSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CatalogWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val out = PlaylistSync.sync(applicationContext)
        if (out.television == 0 && out.radio == 0 && out.egyptDirectory == 0) Result.retry() else Result.success()
    }
}
