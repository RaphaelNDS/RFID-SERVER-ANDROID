package com.example.rfid_server

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Cifra e decifra textos curtos. Lança exceção se não conseguir (chave perdida, Keystore com defeito). */
interface Cifra {
    fun cifrar(texto: String): String
    fun decifrar(cifrado: String): String
}

/**
 * AES-256-GCM com a chave guardada no Android Keystore: a chave nunca sai do aparelho nem aparece nos
 * arquivos do app. Quem copiar o arquivo de preferências (root, build de depuração) vê só o texto cifrado.
 * Formato: Base64(IV de 12 bytes + texto cifrado com a etiqueta GCM).
 */
object CifraKeystore : Cifra {
    private const val PROVEDOR = "AndroidKeyStore"
    private const val APELIDO = "rfid_segredos_v1"
    private const val TRANSFORMACAO = "AES/GCM/NoPadding"
    private const val TAMANHO_IV = 12
    private const val TAMANHO_ETIQUETA_BITS = 128

    @Synchronized
    private fun chave(): SecretKey {
        val ks = KeyStore.getInstance(PROVEDOR).apply { load(null) }
        (ks.getKey(APELIDO, null) as? SecretKey)?.let { return it }

        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVEDOR)
        gerador.init(
            KeyGenParameterSpec.Builder(APELIDO, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gerador.generateKey()
    }

    override fun cifrar(texto: String): String {
        val cipher = Cipher.getInstance(TRANSFORMACAO).apply { init(Cipher.ENCRYPT_MODE, chave()) }
        val cifrado = cipher.doFinal(texto.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + cifrado, Base64.NO_WRAP)
    }

    override fun decifrar(cifrado: String): String {
        val bytes = Base64.decode(cifrado, Base64.NO_WRAP)
        require(bytes.size > TAMANHO_IV) { "texto cifrado curto demais" }
        val cipher = Cipher.getInstance(TRANSFORMACAO)
        cipher.init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(TAMANHO_ETIQUETA_BITS, bytes, 0, TAMANHO_IV))
        return String(cipher.doFinal(bytes, TAMANHO_IV, bytes.size - TAMANHO_IV), Charsets.UTF_8)
    }
}

/**
 * Regras para guardar segredos (chave do coletor, token da sessão) nas preferências:
 *  - grava cifrado, com o prefixo [PREFIXO]. Se o Keystore do aparelho falhar ao cifrar, grava o texto puro
 *    (com aviso no log): o coletor continuar funcionando vale mais que a cifra;
 *  - lê o cifrado; o texto puro de versões anteriores do app é aceito e regravado cifrado ([Lido.regravar]);
 *  - se não der para decifrar (chave do Keystore perdida, ex.: depois de restaurar o aparelho), devolve null:
 *    a chave do coletor precisa ser digitada de novo nas Configurações, e o operador entra de novo.
 */
object SegredoGuardado {
    const val PREFIXO = "cifra1:"
    private const val TAG = "SEGREDO"

    data class Lido(val valor: String?, val regravar: Boolean)

    fun paraGuardar(valor: String, cifra: Cifra, nome: String): String {
        if (valor.isEmpty()) return valor
        return try {
            PREFIXO + cifra.cifrar(valor)
        } catch (e: Exception) {
            Log.e(TAG, "Não foi possível cifrar $nome no Keystore do aparelho: guardado sem cifra", e)
            valor
        }
    }

    fun ler(guardado: String?, cifra: Cifra, nome: String): Lido {
        if (guardado.isNullOrEmpty()) return Lido(null, regravar = false)
        if (!guardado.startsWith(PREFIXO)) return Lido(guardado, regravar = true)
        return try {
            Lido(cifra.decifrar(guardado.removePrefix(PREFIXO)), regravar = false)
        } catch (e: Exception) {
            Log.e(TAG, "Não foi possível decifrar $nome (chave do Keystore perdida?): precisa ser informado de novo", e)
            Lido(null, regravar = false)
        }
    }
}
