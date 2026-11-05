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
import com.example.rfid_server.erros.RegistroErros
import com.example.rfid_server.erros.TipoErro
import java.util.concurrent.TimeUnit

/**
 * Envia as leituras pendentes em segundo plano, mesmo com o app fechado.
 * O Android só executa quando há rede; se o servidor ainda não responder, tenta de novo
 * com espera crescente (30 s, 60 s, 90 s...).
 */
class SincronizacaoWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.i(TAG, "Sincronização em segundo plano iniciada (tentativa ${runAttemptCount + 1})")

        // Os erros guardados vão primeiro: se a passada travar (ex.: banco local cheio), eles já chegaram ao servidor.
        // Não lança erro. Se este envio falhar, o do fim da passada não repete a mesma tentativa (ver FilaErros)
        RegistroErros.enviarPendentes(applicationContext)

        // Sem agendar outra tentativa lá dentro: quem pede a nova tentativa aqui é o Result.retry()
        val estado = try {
            Sincronizador.get(applicationContext).processarPendentes(agendarSeFalhar = false)
        } catch (e: Throwable) {
            // Ex.: banco local sem espaço. Sem isto o WorkManager marcava como falha definitiva e só a periódica voltava
            Log.e(TAG, "Sincronização em segundo plano falhou com erro no coletor: nova tentativa agendada", e)
            RegistroErros.registrar(applicationContext, TipoErro.FALHA_GERAL, "Falha ao sincronizar em segundo plano: ${e.javaClass.simpleName}: ${e.message}", e)
            return Result.retry()
        }

        return when {
            estado == EstadoSincronizacao.SINCRONIZADO -> Result.success()

            !tentarDeNovo(estado) -> {
                Log.w(TAG, "Sincronização em segundo plano parada (${estado.descricao}): volta quando as Configurações " +
                    "forem salvas ou na próxima verificação periódica")
                Result.success()
            }

            else -> {
                Log.w(TAG, "Sincronização em segundo plano não terminou (${estado.descricao}): nova tentativa agendada")
                Result.retry()
            }
        }
    }

    companion object {

        /**
         * Chave, endereço e certificado só mudam nas Configurações (que pedem a sincronização ao salvar): repetir
         * com espera crescente só acordaria o coletor à toa. A periódica de 15 min continua conferindo.
         */
        internal fun tentarDeNovo(estado: EstadoSincronizacao): Boolean =
            estado != EstadoSincronizacao.SINCRONIZADO && !estado.dependeDasConfiguracoes

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
