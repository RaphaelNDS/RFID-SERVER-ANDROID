package com.example.rfid_server

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.example.rfid_server.dados.BancoLocal
import com.example.rfid_server.sincronizacao.SincronizadorJustificativas
import com.example.rfid_server.sincronizacao.SincronizadorTransferencias
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
    /** Grava os valores; devolve false se não ficou gravado (ex.: armazenamento cheio). */
    private val gravar: (Map<String, String>) -> Boolean,
    private val todas: () -> Map<String, String>,
    private val apagar: (Collection<String>) -> Unit,
    private val cifra: () -> Cifra,
    private val relogio: () -> Long = System::currentTimeMillis,
    /**
     * Referências ([PREFIXO]...) ainda usadas no banco local (leituras, inventários, itens e cadastros pendentes):
     * essas nunca são apagadas por [limparAntigas], mesmo que pareçam velhas (ex.: o relógio do aparelho pulou para
     * frente). Se a consulta falhar, nada é apagado.
     */
    private val emUso: () -> Set<String> = { emptySet() }
) {

    private val tokens = ConcurrentHashMap<String, String>()

    /** Referência para guardar no banco no lugar do [token]. Guarda o token cifrado na primeira vez. */
    fun referencia(token: String): String {
        val ref = PREFIXO + hash(token)
        if (tokens.putIfAbsent(ref, token) == null) {
            val gravou = try {
                gravar(mapOf(ref to SegredoGuardado.paraGuardar(token, cifra(), "o token da sessão"), ref + SUFIXO_DATA to relogio().toString()))
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao guardar o token da sessão no aparelho", e)
                false
            }
            // Sem isso, o token ficava só na memória e nunca era gravado de novo: ao reabrir o app as leituras desta
            // sessão iam sem o token e eram recusadas. Tira da memória para a próxima leitura tentar gravar outra vez
            if (!gravou) {
                Log.e(TAG, "Token da sessão não ficou guardado no aparelho (armazenamento cheio?): nova tentativa na próxima leitura")
                tokens.remove(ref)
            }
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

    /**
     * Apaga os tokens guardados há mais de [GUARDA_MS]: o servidor já não aceita ações tão antigas. A idade é medida
     * pelo relógio do aparelho, que pode estar errado: por isso as referências ainda usadas no banco ([emUso]) ficam
     * sempre (sem o token, a leitura na fila seria recusada pela sessão).
     */
    fun limparAntigas(): Int {
        val limite = relogio() - GUARDA_MS
        val candidatas = todas().filterKeys { it.endsWith(SUFIXO_DATA) }
            .filterValues { (it.toLongOrNull() ?: 0) < limite }
            .keys.map { it.removeSuffix(SUFIXO_DATA) }
        if (candidatas.isEmpty()) return 0

        val usadas = try {
            emUso()
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível conferir as sessões ainda usadas no aparelho: nenhuma é apagada agora", e)
            return 0
        }
        val vencidas = candidatas.filter { it !in usadas }
        if (vencidas.size < candidatas.size) {
            Log.w(TAG, "${candidatas.size - vencidas.size} sessão(ões) com mais de ${GUARDA_MS / 86_400_000} dias ainda têm " +
                "registros na fila: ficam guardadas (o relógio do aparelho pode estar adiantado)")
        }
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

        // Precisa do resultado do commit(), que a extensão edit { } do KTX não devolve
        @SuppressLint("UseKtx")
        private fun gravarConferindo(prefs: SharedPreferences, valores: Map<String, String>): Boolean =
            prefs.edit().apply { valores.forEach { (k, v) -> putString(k, v) } }.commit()

        fun get(context: Context): CofreSessoes =
            instancia ?: synchronized(this) {
                instancia ?: context.applicationContext.let { app ->
                    val prefs = app.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)
                    CofreSessoes(
                        ler = { prefs.getString(it, null) },
                        // commit(): a linha do banco vai apontar para esta referência logo em seguida
                        gravar = { valores -> gravarConferindo(prefs, valores) },
                        todas = { prefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap() },
                        apagar = { chaves -> prefs.edit { chaves.forEach { remove(it) } } },
                        cifra = { AppConfig.cifra },
                        // Referências ainda gravadas no banco local (inclusive recusadas guardadas): o token delas fica
                        emUso = {
                            val banco = BancoLocal.get(app)
                            // Também as filas em arquivo (justificativas e transferências pendentes): sem o token, o
                            // envio iria com a referência que não se resolve e a transferência seria recusada
                            (banco.leituraDao().sessoesEmUso() + banco.inventarioDao().sessoesEmUso() +
                                banco.cadastroDao().sessoesEmUso() +
                                SincronizadorJustificativas.fila(app).pendentes().mapNotNull { it.sessao } +
                                SincronizadorTransferencias.fila(app).pendentes().mapNotNull { it.sessao }).toSet()
                        }
                    ).also { cofre ->
                        // Fora da thread de quem pediu (pode ser a tela): o Room não aceita consulta na thread principal
                        Thread({ cofre.limparAntigas() }, "limpeza-cofre-sessoes").start()
                    }
                }.also { instancia = it }
            }
    }
}
