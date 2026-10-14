package com.example.rfid_server

import android.app.Application
import android.util.Log
import com.example.rfid_server.erros.RegistroErros
import com.example.rfid_server.erros.TipoErro

/**
 * Início do app: guarda os travamentos (exceção não tratada) para enviar ao servidor na próxima vez que houver
 * rede. O comportamento do Android não muda: depois de guardar, o tratamento padrão fecha o app como antes.
 */
class RfidApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val padrao = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, erro ->
            try {
                Log.e(TAG, "O app travou (thread ${thread.name}): o erro vai para o servidor na próxima sincronização", erro)
                RegistroErros.registrar(this, TipoErro.FALHA_GERAL, "${erro.javaClass.simpleName}: ${erro.message.orEmpty()}", erro)
            } catch (_: Throwable) {
                // Nada aqui pode impedir o tratamento padrão
            }
            padrao?.uncaughtException(thread, erro)
        }
    }

    companion object {
        private const val TAG = "APP"
    }
}
