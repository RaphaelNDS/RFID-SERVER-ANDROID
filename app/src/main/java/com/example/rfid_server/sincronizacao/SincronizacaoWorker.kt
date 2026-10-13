package com.example.rfid_server.sincronizacao

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Envia as leituras pendentes em segundo plano, mesmo com o app fechado.
 * O Android só executa quando há rede; se o servidor ainda não responder, tenta de novo
 * com espera crescente (30 s, 60 s, 90 s...).
 */
class SincronizacaoWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.i(TAG, "Sincronização em segundo plano iniciada (tentativa ${runAttemptCount + 1})")

        val estado = Sincronizador.get(applicationContext).processarPendentes()

        return if (estado == EstadoSincronizacao.SINCRONIZADO) {
            Result.success()
        } else {
            Log.w(TAG, "Sincronização em segundo plano não terminou (${estado.descricao}): nova tentativa agendada")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SINCRONIZACAO"
        private const val NOME_TENTATIVA = "sincronizar-leituras"
        private const val NOME_PERIODICA = "sincronizar-leituras-periodica"

        private val comRede = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        /** Agenda uma tentativa para quando houver rede. Se já houver uma agendada, mantém a existente. */
        fun agendarNovaTentativa(context: Context) {
            val pedido = OneTimeWorkRequest.Builder(SincronizacaoWorker::class.java)
                .setConstraints(comRede)
                .setInitialDelay(30, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(NOME_TENTATIVA, ExistingWorkPolicy.KEEP, pedido)

            Log.d(TAG, "Nova tentativa de sincronização agendada para quando houver rede")
        }

        /** Rede de segurança: a cada 15 min (mínimo do Android), confere se sobrou algo para enviar. */
        fun agendarPeriodica(context: Context) {
            val pedido = PeriodicWorkRequest.Builder(SincronizacaoWorker::class.java, 15, TimeUnit.MINUTES)
                .setConstraints(comRede)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NOME_PERIODICA, ExistingPeriodicWorkPolicy.KEEP, pedido)
        }
    }
}
