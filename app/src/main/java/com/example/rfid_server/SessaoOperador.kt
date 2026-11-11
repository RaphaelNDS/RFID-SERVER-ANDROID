package com.example.rfid_server

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.rfid_server.erros.MascaraSensivel
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Operador logado neste coletor (POST /api/android/login). A sessão vale até [expiraEm] mesmo sem
 * rede; o token vai junto de cada leitura/inventário/cadastro, então a autoria fica certa mesmo que
 * o envio aconteça depois de a sessão expirar.
 */
data class SessaoOperador(
    val token: String,
    val expiraEm: Long,
    val nome: String,
    val email: String,
    val podeLer: Boolean,
    /** Operador com permissão de cadastro E coletor liberado no servidor. */
    val podeCadastrar: Boolean,
    val senhaFraca: Boolean = false,
    /** Relógio do aparelho na hora do login (null = sessão guardada por uma versão anterior). */
    val entrouEm: Long? = null,
    /** Tempo desde a ligação do aparelho (elapsedRealtime) na hora do login. */
    val entrouDecorrido: Long? = null,
    /** Operador pode transferir bens de local (RFID · Editar no servidor 1.7.0); servidor que não manda = não. */
    val podeTransferir: Boolean = false
) {
    /**
     * A sessão vale em [agora] (relógio do aparelho)? Também confere o tempo desde a ligação ([agoraDecorrido]), que
     * não muda quando alguém atrasa o relógio: sem rede, atrasar o relógio mantinha a sessão vencida valendo (o PIN já
     * se protegia assim). Depois de reiniciar o aparelho o tempo desde a ligação recomeça e só o relógio vale.
     */
    fun validaEm(agora: Long, agoraDecorrido: Long? = null): Boolean {
        if (agora >= expiraEm) return false
        val inicio = entrouEm ?: return true
        val inicioDecorrido = entrouDecorrido ?: return true
        if (agoraDecorrido == null || agoraDecorrido < inicioDecorrido) return true
        return agoraDecorrido - inicioDecorrido < expiraEm - inicio
    }

    /**
     * Quando a sessão vence, no relógio do aparelho, considerando também o tempo desde o login (como [validaEm]): com o
     * relógio atrasado ela vence antes do [expiraEm]. Usado para conferir logo no fim (AvisoSessao).
     */
    fun fimEm(agora: Long, agoraDecorrido: Long? = null): Long {
        val inicio = entrouEm ?: return expiraEm
        val inicioDecorrido = entrouDecorrido ?: return expiraEm
        if (agoraDecorrido == null || agoraDecorrido < inicioDecorrido) return expiraEm
        val restantePeloDecorrido = (expiraEm - inicio) - (agoraDecorrido - inicioDecorrido)
        return minOf(expiraEm, agora + restantePeloDecorrido)
    }

    companion object {
        /** Resposta do servidor ao login. Lança org.json.JSONException ou DateTimeParseException se vier fora do formato. */
        fun deJson(json: String): SessaoOperador {
            val o = JSONObject(json)
            return SessaoOperador(
                token = o.getString("token"),
                expiraEm = OffsetDateTime.parse(o.getString("expiraEm")).toInstant().toEpochMilli(),
                nome = o.getString("nome"),
                email = o.getString("email"),
                podeLer = o.getBoolean("podeLer"),
                podeCadastrar = o.getBoolean("podeCadastrar"),
                senhaFraca = o.optBoolean("senhaFraca", false),
                podeTransferir = o.optBoolean("podeTransferir", false)
            )
        }
    }
}

/** Sessão atual do coletor, guardada nas preferências (fora do backup, junto com a chave). */
object SessaoAtual {

    private const val TAG = "SESSAO"

    /** A sessão, se existir e não tiver expirado. Expirada, é apagada (o app volta para a tela de login). */
    fun get(
        context: Context,
        agora: Long = System.currentTimeMillis(),
        agoraDecorrido: Long = SystemClock.elapsedRealtime()
    ): SessaoOperador? {
        val sessao = AppConfig.getSessao(context) ?: return null
        if (sessao.validaEm(agora, agoraDecorrido)) return sessao

        if (agora < sessao.expiraEm) {
            Log.w(TAG, "Sessão de ${MascaraSensivel.email(sessao.email)} expirou pelo tempo desde o login, embora o relógio do aparelho diga que não (relógio atrasado?): é preciso entrar de novo")
        } else {
            Log.i(TAG, "Sessão de ${MascaraSensivel.email(sessao.email)} expirou: é preciso entrar de novo")
        }
        // Só se ainda for a mesma: uma sessão renovada agora por outra thread (tela de aviso, login) não é apagada
        if (!AppConfig.limparSessaoSeFor(context, sessao.token)) {
            return AppConfig.getSessao(context)?.takeIf { it.validaEm(agora, agoraDecorrido) }
        }
        // Quem descobriu pode ser a leitura do gatilho (outra thread): a tela aberta vai para o login na hora
        avisarOuvintes()
        return null
    }

    fun token(context: Context): String? = get(context)?.token

    // Telas abertas (AvisoSessao) que querem saber na hora que a sessão acabou (encerrada pelo servidor ou vencida)
    private val ouvintes = CopyOnWriteArraySet<() -> Unit>()

    fun ouvirEncerramento(ouvinte: () -> Unit) { ouvintes += ouvinte }

    fun pararDeOuvir(ouvinte: () -> Unit) { ouvintes -= ouvinte }

    /**
     * O servidor recusou de vez a sessão [token] (ver sincronizacao.FimDaSessaoAtual): encerra, se ela ainda for a
     * atual, guarda o [motivoServidor] para a tela de login e avisa as telas abertas. Devolve true se encerrou.
     * Chamado na thread da sincronização: os ouvintes cuidam de passar para a thread principal.
     */
    fun encerrarPeloServidor(context: Context, token: String, motivoServidor: String): Boolean {
        if (!AppConfig.encerrarSessaoRecusada(context, token, motivoServidor)) return false
        avisarOuvintes()
        return true
    }

    private fun avisarOuvintes() {
        ouvintes.forEach { ouvinte ->
            try {
                ouvinte()
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao avisar uma tela do fim da sessão", e)
            }
        }
    }
}
