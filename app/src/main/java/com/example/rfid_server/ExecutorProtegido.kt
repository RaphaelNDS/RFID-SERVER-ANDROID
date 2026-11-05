package com.example.rfid_server

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.example.rfid_server.erros.RegistroErros
import com.example.rfid_server.erros.TipoErro
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Executor que não deixa uma exceção derrubar o app. Num executor comum, uma exceção dentro da tarefa vai para o
 * tratador de travamentos do Android e o processo morre: com a memória do coletor cheia (o banco não consegue
 * gravar) o app fechava a cada etiqueta lida e a cada abertura da tela principal, em loop. Aqui a tarefa que falhou
 * é perdida (o que não foi gravado não estava guardado de qualquer forma), o erro vai para [aoErro] e o resto segue.
 */
class ExecutorProtegido(
    private val base: Executor,
    private val nome: String,
    private val aoErro: (String, Throwable) -> Unit
) : Executor {

    override fun execute(tarefa: Runnable) {
        base.execute {
            try {
                tarefa.run()
            } catch (e: Throwable) {
                Log.e(TAG, "Falha na tarefa de $nome: o app continua aberto", e)
                runCatching { aoErro(nome, e) }
            }
        }
    }

    companion object {
        private const val TAG = "APP"

        // Um aviso por vez: com o banco travado, cada etiqueta lida daria um aviso
        private val limitador = LimitadorAviso(30_000)

        /** Uma thread só, com o erro registrado para o servidor e um aviso na tela do operador. */
        fun umaThread(context: Context, nome: String): ExecutorProtegido {
            val app = context.applicationContext
            return ExecutorProtegido(Executors.newSingleThreadExecutor(), nome) { tarefa, e ->
                RegistroErros.registrar(app, TipoErro.FALHA_GERAL, "Falha ao $tarefa no coletor: ${e.javaClass.simpleName}: ${e.message}", e)
                if (limitador.liberar()) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(app, app.getString(R.string.falha_gravar_coletor), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }
}
