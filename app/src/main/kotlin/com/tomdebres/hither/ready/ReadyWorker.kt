package com.tomdebres.hither.ready

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tomdebres.hither.HitherApplication
import com.tomdebres.hither.core.rules.BACKGROUND_LOOK_MINUTES
import java.util.concurrent.TimeUnit

/**
 * The background refresh (DECISIONS 76): every 30 minutes while there is a network, one look at the
 * user's requests and the home screen brought up to date. Without a stored account it does nothing.
 * It never retries sooner: a failed look waits for the next period, so the server is never woken more
 * often than every 30 minutes from the background.
 */
class ReadyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as HitherApplication
        if (app.gateway.resume()) app.ready.look(background = true)
        return Result.success()
    }

    companion object {
        const val NAME = "ready-look"

        /** Schedules the refresh, keeping one already scheduled (its period is updated if it changed). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReadyWorker>(BACKGROUND_LOOK_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
