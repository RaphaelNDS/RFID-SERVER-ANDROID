package com.example.rfid_server.sincronizacao

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest

/**
 * Quando o servidor recusa a SESSÃO de um operador (401 SESSAO_OPERADOR), o coletor não tem como saber se é
 * definitivo (usuário desativado, sessão expirada) ou um problema do lado do servidor (banco restaurado sem as
 * sessões, servidor levado para outra máquina com o coletor configurado com outro nome). Por isso as leituras da
 * sessão recusada ficam na fila por um prazo ([Sincronizador.PRAZO_SESSAO_RECUSADA_MS]) contado da primeira recusa;
 * só depois disso viram recusadas. Aqui fica guardado quando cada sessão foi recusada pela primeira vez.
 */
interface RecusasDeSessao {
    /** Quando a [sessao] foi recusada pela primeira vez (epoch ms), ou null se não está recusada. */
    fun primeiraRecusa(sessao: String): Long?

    fun registrar(sessao: String, momento: Long)

    /** O servidor aceitou a [sessao]: a sequência de recusas acabou. */
    fun esquecer(sessao: String)

    /** Esquece as sessões que não têm mais leituras na fila. */
    fun manterSomente(sessoes: Set<String>)
}

/** Usada nos testes (e como padrão): some quando o processo morre. */
class RecusasDeSessaoEmMemoria : RecusasDeSessao {
    private val recusas = mutableMapOf<String, Long>()

    override fun primeiraRecusa(sessao: String) = recusas[sessao]
    override fun registrar(sessao: String, momento: Long) { recusas[sessao] = momento }
    override fun esquecer(sessao: String) { recusas.remove(sessao) }
    override fun manterSomente(sessoes: Set<String>) { recusas.keys.retainAll(sessoes) }
}

/**
 * Guardada nas preferências do app: o prazo continua contando mesmo se o app fechar. A chave é o SHA-256 do
 * token (o token em si já fica no banco local, não precisa de mais uma cópia).
 */
class RecusasDeSessaoGuardadas(context: Context) : RecusasDeSessao {
    private val prefs = context.applicationContext.getSharedPreferences(ARQUIVO, Context.MODE_PRIVATE)

    override fun primeiraRecusa(sessao: String): Long? {
        val chave = chave(sessao)
        return if (prefs.contains(chave)) prefs.getLong(chave, 0) else null
    }

    override fun registrar(sessao: String, momento: Long) {
        prefs.edit { putLong(chave(sessao), momento) }
    }

    override fun esquecer(sessao: String) {
        val chave = chave(sessao)
        if (prefs.contains(chave)) prefs.edit { remove(chave) }
    }

    override fun manterSomente(sessoes: Set<String>) {
        val manter = sessoes.map { chave(it) }.toSet()
        val sobrando = prefs.all.keys - manter
        if (sobrando.isEmpty()) return
        prefs.edit { sobrando.forEach { remove(it) } }
    }

    private fun chave(sessao: String): String =
        MessageDigest.getInstance("SHA-256").digest(sessao.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        const val ARQUIVO = "recusas_sessao"
    }
}
