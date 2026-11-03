package com.example.rfid_server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN das Configurações do coletor: protege o IP do servidor, a chave do coletor e a confirmação do
 * certificado contra quem pega o coletor na mão. Só o hash (PBKDF2 com sal aleatório) fica no aparelho.
 * Errar [MAX_ERROS] vezes seguidas bloqueia novas tentativas por [BLOQUEIO_MS].
 */
object PinColetor {

    const val TAMANHO_MINIMO = 4
    const val TAMANHO_MAXIMO = 8
    const val MAX_ERROS = 5
    const val BLOQUEIO_MS = 5L * 60 * 1000

    // Lento de propósito: com só 10^4 a 10^8 PINs possíveis, cada tentativa num hash copiado do aparelho
    // precisa custar caro. ~50 ms por conferência no MC3300x, que só acontece ao salvar as Configurações.
    private const val ITERACOES = 20_000

    fun formatoValido(pin: String): Boolean = pin.length in TAMANHO_MINIMO..TAMANHO_MAXIMO && pin.all { it in '0'..'9' }

    /** Problema do PIN novo para mostrar ao responsável, ou null se estiver bom. */
    fun problemaDoNovo(pin: String, confirmacao: String): String? = when {
        !formatoValido(pin) -> "O PIN precisa ter de $TAMANHO_MINIMO a $TAMANHO_MAXIMO números"
        pin.toSet().size == 1 -> "PIN fácil demais: não use o mesmo número repetido"
        pin in "0123456789" || pin in "9876543210" -> "PIN fácil demais: não use números em sequência"
        pin != confirmacao -> "Os dois PINs não são iguais"
        else -> null
    }

    fun novoSal(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.let { Base64.getEncoder().encodeToString(it) }

    fun hash(pin: String, sal: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), Base64.getDecoder().decode(sal), ITERACOES, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** Comparação em tempo constante. */
    fun confere(pin: String, sal: String, hashGuardado: String): Boolean =
        MessageDigest.isEqual(hash(pin, sal).toByteArray(), hashGuardado.toByteArray())

    /** Até quando novas tentativas ficam bloqueadas depois de mais um erro (null = ainda pode tentar). */
    fun bloqueioAposErro(errosSeguidos: Int, agora: Long): Long? =
        if (errosSeguidos >= MAX_ERROS) agora + BLOQUEIO_MS else null
}
