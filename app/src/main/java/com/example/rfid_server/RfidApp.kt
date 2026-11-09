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

        aquecerSegredos()
        // Leitor de código de barras entregando ao app (ver CodigoDeBarras); sem DataWedge, não faz nada
        CodigoDeBarras.configurarPerfil(this)
    }

    /**
     * Abre o Keystore fora da thread principal: decifrar a sessão e a chave do coletor (e, no primeiro uso, criar a
     * chave do Keystore e regravar cifrado o texto puro antigo) leva dezenas de milissegundos. Depois disso os valores
     * ficam no cache do AppConfig e a primeira tela não espera. O CofreSessoes já fica pronto para a primeira leitura.
     * Se falhar aqui, nada muda: cada um tenta de novo quando for usado.
     */
    private fun aquecerSegredos() {
        // Nos testes (Robolectric cria este Application em cada teste) não há Keystore, e a thread mexeria no cache
        // do AppConfig e no CofreSessoes enquanto os próprios testes usam os falsos
        if (android.os.Build.FINGERPRINT == "robolectric") return
        val app = this
        Thread({
            try {
                val inicio = android.os.SystemClock.elapsedRealtime()
                AppConfig.getSessao(app)
                AppConfig.getChaveDispositivo(app)
                CofreSessoes.get(app)
                Log.d(TAG, "Segredos preparados em segundo plano em ${android.os.SystemClock.elapsedRealtime() - inicio} ms")
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível preparar os segredos em segundo plano: serão lidos quando forem usados", e)
            }
        }, "aquecer-segredos").start()
    }

    companion object {
        private const val TAG = "APP"
    }
}
