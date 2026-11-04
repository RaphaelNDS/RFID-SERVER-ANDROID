package com.example.rfid_server.erros

import android.content.Context
import android.util.Log
import com.example.rfid_server.BuildConfig
import com.example.rfid_server.SessaoAtual
import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService
import org.json.JSONArray
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Erros do app que vão para o servidor (tela Coletores → Erros): guarda na [FilaErros] e envia quando há rede
 * ([enviarPendentes], chamado pelo Sincronizador e pela tela de login). Nunca lança erro: registrar ou enviar
 * um erro não pode causar outro.
 */
object RegistroErros {

    private const val TAG = "ERROS_APP"

    @Volatile
    private var fila: FilaErros? = null

    private fun fila(context: Context): FilaErros =
        fila ?: synchronized(this) {
            fila ?: FilaErros(
                File(context.applicationContext.filesDir, "erros_pendentes.json"),
                BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE
            ).also { fila = it }
        }

    /** Guarda um erro para enviar ao servidor. [erro], se houver, vira o detalhe (stack trace, sem dados sensíveis). */
    fun registrar(context: Context, tipo: String, mensagem: String, erro: Throwable? = null) {
        try {
            val operador = runCatching { SessaoAtual.get(context)?.nome }.getOrNull()
            fila(context).registrar(tipo, mensagem, erro?.let(::stackTrace), operador)
            Log.w(TAG, "Erro registrado para enviar ao servidor: $tipo - ${MascaraSensivel.limpar(mensagem).take(200)}")
        } catch (e: Throwable) {
            Log.e(TAG, "Não foi possível registrar o erro do app ($tipo)", e)
        }
    }

    /** Envia ao servidor os erros guardados (bloqueante: chame fora da thread principal). */
    fun enviarPendentes(context: Context) {
        try {
            val servico = TagService(context)
            enviarPendentes(fila(context), servico::enviarErrosAgora)
        } catch (e: Throwable) {
            Log.e(TAG, "Falha ao enviar os erros do app ao servidor", e)
        }
    }

    /** Um envio da [fila] (separado do Context para os testes). */
    internal fun enviarPendentes(fila: FilaErros, enviar: (JSONArray) -> RespostaServidor<Unit>) {
        val lote = fila.pendentes()
        if (lote.length() == 0) return

        when (val resposta = enviar(lote)) {
            is RespostaServidor.Sucesso -> {
                // Só o que foi enviado: um erro repetido durante o envio continua na fila com as ocorrências novas
                val removidos = fila.confirmarEnvio(lote)
                Log.i(TAG, "$removidos erro(s) do app enviado(s) ao servidor (${fila.quantidade()} ainda guardado(s))")
            }
            RespostaServidor.NaoEncontrado ->
                Log.d(TAG, "Servidor sem a rota de erros (versão antiga): ${lote.length()} erro(s) continuam guardados")
            is RespostaServidor.Falha ->
                if (resposta.motivo == MotivoFalha.REQUISICAO_RECUSADA) {
                    // Recusa definitiva deste lote (ex.: 400 por um campo que o servidor não aceita): reenviar daria
                    // a mesma recusa e os mesmos erros travariam a fila para sempre. Descarta e segue com os próximos
                    fila.confirmarEnvio(lote)
                    Log.w(TAG, "Servidor recusou ${lote.length()} erro(s) do app (${resposta.mensagem}): descartados para não travar os outros")
                } else {
                    Log.d(TAG, "Erros do app não enviados agora (${resposta.mensagem}): ficam guardados para a próxima")
                }
        }
    }

    private fun stackTrace(erro: Throwable): String = StringWriter().also { erro.printStackTrace(PrintWriter(it)) }.toString()
}
