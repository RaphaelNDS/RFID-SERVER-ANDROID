package com.example.rfid_server

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.time.OffsetDateTime

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
    val senhaFraca: Boolean = false
) {
    fun validaEm(agora: Long) = agora < expiraEm

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
                senhaFraca = o.optBoolean("senhaFraca", false)
            )
        }
    }
}

/** Sessão atual do coletor, guardada nas preferências (fora do backup, junto com a chave). */
object SessaoAtual {

    private const val TAG = "SESSAO"

    /** A sessão, se existir e não tiver expirado. Expirada, é apagada (o app volta para a tela de login). */
    fun get(context: Context, agora: Long = System.currentTimeMillis()): SessaoOperador? {
        val sessao = AppConfig.getSessao(context) ?: return null
        if (sessao.validaEm(agora)) return sessao

        Log.i(TAG, "Sessão de ${sessao.email} expirou: é preciso entrar de novo")
        AppConfig.limparSessao(context)
        return null
    }

    fun token(context: Context): String? = get(context)?.token
}
