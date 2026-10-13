package com.example.rfid_server.api

import android.util.Log
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/** O servidor apresentou um certificado e ninguém confirmou ainda: as Configurações mostram [impressaoDigital]. */
class CertificadoNaoConfirmadoException(val impressaoDigital: String) :
    CertificateException("Certificado do servidor ainda não confirmado neste coletor")

/** O servidor apresentou um certificado DIFERENTE do confirmado: pode ser troca legítima ou alguém se passando pelo servidor. */
class CertificadoDiferenteException(val impressaoDigital: String, val esperada: String) :
    CertificateException("O certificado do servidor mudou")

/**
 * Confiança do HTTPS por certificado fixado: o servidor usa um certificado próprio (autoassinado), que
 * nenhuma autoridade assina. Em vez de confiar em qualquer um, o coletor só aceita o certificado cuja
 * impressão digital SHA-256 o responsável conferiu na tela Status do servidor e confirmou nas Configurações.
 *
 * O nome/IP do certificado não é conferido (o IP do servidor pode mudar): quem garante que é o servidor
 * certo é a impressão digital, que é única daquele certificado.
 */
class ConfiancaCertificado(private val impressaoConfirmada: () -> String?) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificado = chain?.firstOrNull() ?: throw CertificateException("O servidor não apresentou certificado")
        val apresentada = impressaoDigital(certificado)
        val esperada = impressaoConfirmada()

        when {
            esperada == null -> {
                Log.w(TAG, "Certificado do servidor ainda não confirmado: $apresentada")
                throw CertificadoNaoConfirmadoException(apresentada)
            }

            !apresentada.equals(esperada, ignoreCase = true) -> {
                Log.e(TAG, "Certificado do servidor DIFERENTE do confirmado (esperado $esperada, recebido $apresentada): conexão recusada")
                throw CertificadoDiferenteException(apresentada, esperada)
            }
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("O coletor não aceita conexões de entrada")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        private const val TAG = "CERTIFICADO"

        /** SHA-256 do certificado (DER), em hexadecimal com ":", igual ao mostrado no log e na tela Status do servidor. */
        fun impressaoDigital(certificado: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificado.encoded).joinToString(":") { "%02X".format(it) }

        /** Procura, nas causas de uma falha de conexão, a recusa do certificado (o OkHttp embrulha em SSLHandshakeException). */
        fun recusaNaCausa(erro: Throwable): CertificateException? =
            generateSequence(erro) { it.cause }.take(10)
                .firstOrNull { it is CertificadoNaoConfirmadoException || it is CertificadoDiferenteException } as CertificateException?
    }
}
