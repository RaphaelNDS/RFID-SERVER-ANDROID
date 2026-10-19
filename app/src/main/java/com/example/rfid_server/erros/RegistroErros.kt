package com.example.rfid_server.erros

import android.content.Context
import android.util.Log
import com.example.rfid_server.BuildConfig
import com.example.rfid_server.SessaoAtual
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService
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
            val fila = fila(context)
            val lote = fila.pendentes()
            if (lote.length() == 0) return

            when (val resposta = TagService(context).enviarErrosAgora(lote)) {
                is RespostaServidor.Sucesso -> {
                    val ids = (0 until lote.length()).map { lote.getJSONObject(it).getString("id") }
                    fila.remover(ids)
                    Log.i(TAG, "${ids.size} erro(s) do app enviado(s) ao servidor (${fila.quantidade()} ainda guardado(s))")
                }
                RespostaServidor.NaoEncontrado ->
                    Log.d(TAG, "Servidor sem a rota de erros (versão antiga): ${lote.length()} erro(s) continuam guardados")
                is RespostaServidor.Falha ->
                    Log.d(TAG, "Erros do app não enviados agora (${resposta.mensagem}): ficam guardados para a próxima")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Falha ao enviar os erros do app ao servidor", e)
        }
    }

    private fun stackTrace(erro: Throwable): String = StringWriter().also { erro.printStackTrace(PrintWriter(it)) }.toString()
}
