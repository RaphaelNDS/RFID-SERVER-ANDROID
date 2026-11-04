package com.example.rfid_server

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Tira o token da sessão do operador do banco local. Cada leitura, inventário e cadastro guarda quem fez (o token
 * vai junto no envio), e antes o token ficava em texto puro em cada linha do banco. Agora o banco guarda só uma
 * **referência** ([PREFIXO] + parte do SHA-256 do token), que não serve como token, e o token fica aqui, cifrado
 * (ver [SegredoGuardado]). Na hora do envio o HttpClient troca a referência pelo token.
 *
 * A referência é sempre a mesma para o mesmo token: o agrupamento por operador do Sincronizador continua igual.
 * Linhas gravadas antes da 1.6.1 têm o token puro e continuam funcionando (passam direto por [token]).
 */
class CofreSessoes internal constructor(
    private val ler: (String) -> String?,
    private val gravar: (Map<String, String>) -> Unit,
    private val todas: () -> Map<String, String>,
    private val apagar: (Collection<String>) -> Unit,
    private val cifra: () -> Cifra,
    private val relogio: () -> Long = System::currentTimeMillis
) {

    private val tokens = ConcurrentHashMap<String, String>()

    /** Referência para guardar no banco no lugar do [token]. Guarda o token cifrado na primeira vez. */
    fun referencia(token: String): String {
        val ref = PREFIXO + hash(token)
        if (tokens.putIfAbsent(ref, token) == null) {
            gravar(mapOf(ref to SegredoGuardado.paraGuardar(token, cifra(), "o token da sessão"), ref + SUFIXO_DATA to relogio().toString()))
        }
        return ref
    }

    /**
     * Token de uma [referencia] guardada no banco. Valor que não é referência (linha de antes da 1.6.1, sessão atual)
     * volta igual. Referência que não dá para resolver (cofre apagado, chave de cifra perdida) também volta igual: o
     * servidor recusa a sessão e o Sincronizador trata como sessão recusada (espera o prazo e depois recusa).
     */
    fun token(referencia: String): String {
        if (!referencia.startsWith(PREFIXO)) return referencia
        tokens[referencia]?.let { return it }

        val token = SegredoGuardado.ler(ler(referencia), cifra(), "o token da sessão").valor
        if (token == null) {
            Log.e(TAG, "Sessão guardada no aparelho não encontrada ou ilegível: o envio vai sem o token e o servidor vai recusar")
            return referencia
        }
        tokens[referencia] = token
        return token
    }

    /** Apaga os tokens guardados há mais de [GUARDA_MS]: o servidor já não aceita ações tão antigas. */
    fun limparAntigas(): Int {
        val limite = relogio() - GUARDA_MS
        val vencidas = todas().filterKeys { it.endsWith(SUFIXO_DATA) }
            .filterValues { (it.toLongOrNull() ?: 0) < limite }
            .keys.map { it.removeSuffix(SUFIXO_DATA) }
        if (vencidas.isEmpty()) return 0
        apagar(vencidas.flatMap { listOf(it, it + SUFIXO_DATA) })
        vencidas.forEach { tokens.remove(it) }
        Log.i(TAG, "${vencidas.size} sessão(ões) antiga(s) apagada(s) do cofre do aparelho")
        return vencidas.size
    }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    companion object {
        const val PREFIXO = "sessao1:"
        private const val SUFIXO_DATA = "#em"
        private const val TAG = "SEGREDO"
        private const val ARQUIVO = "cofre_sessoes"

        /** 30 dias: bem mais que o prazo de sincronização do servidor (72 h por padrão). */
        internal const val GUARDA_MS = 30L * 24 * 60 * 60 * 1000

        @Volatile
        private var instancia: CofreSessoes? = null

        fun get(context: Context): CofreSessoes =
            instancia ?: synchronized(this) {
                instancia ?: context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE).let { prefs ->
                    CofreSessoes(
                        ler = { prefs.getString(it, null) },
                        // commit(): a linha do banco vai apontar para esta referência logo em seguida
                        gravar = { valores -> prefs.edit(commit = true) { valores.forEach { (k, v) -> putString(k, v) } } },
                        todas = { prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap() },
                        apagar = { chaves -> prefs.edit { chaves.forEach { remove(it) } } },
                        cifra = { AppConfig.cifra }
                    ).also { it.limparAntigas() }
                }.also { instancia = it }
            }
    }
}
