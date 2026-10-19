package com.example.rfid_server.api

import android.content.Context
import android.util.Log
import com.example.rfid_server.AppConfig
import com.example.rfid_server.BuildConfig
import com.example.rfid_server.SessaoAtual
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * Cliente HTTP único do app: todas as chamadas ao servidor passam por aqui.
 *
 * - Envia a chave do coletor no header X-Chave-Dispositivo (lida a cada requisição,
 *   então uma chave nova salva nas Configurações vale na hora).
 * - Envia a sessão do operador (X-Sessao-Operador):
 *   - consultas da tela (catálogo, locais, patrimônio...) vão com a sessão ATUAL;
 *   - envios da fila já trazem a sessão de quem fez a ação (TagService) e ela é respeitada;
 *     header em branco = registro antigo, sem operador: o header é removido.
 * - NÃO segue redirecionamentos: se o servidor mandar para a tela de login, o app recebe
 *   o 302 e trata como falha, em vez de ler o HTML do login como se fosse sucesso.
 */
object HttpClient {

    const val HEADER_CHAVE = "X-Chave-Dispositivo"
    const val HEADER_SESSAO = "X-Sessao-Operador"
    const val HEADER_MOMENTO = "X-Momento"
    const val HEADER_VERSAO_APP = "X-Versao-App"
    const val HEADER_VERSAO_CODIGO = "X-Versao-Codigo"

    private const val TAG = "HTTP_CLIENT"

    @Volatile
    private var client: OkHttpClient? = null

    fun get(context: Context): OkHttpClient =
        client ?: synchronized(this) {
            client ?: context.applicationContext.let { app ->
                criar(
                    sessaoAtual = { SessaoAtual.token(app) },
                    impressaoConfirmada = { AppConfig.getImpressaoDigital(app) },
                    chaveDispositivo = { AppConfig.getChaveDispositivo(app) }
                )
            }.also { client = it }
        }

    // Chave, sessão e certificado confirmado são lidos a cada uso (lambdas): mudar nas Configurações vale na hora
    internal fun criar(
        sessaoAtual: () -> String? = { null },
        impressaoConfirmada: () -> String? = { null },
        chaveDispositivo: () -> String
    ): OkHttpClient {
        // HTTPS: só aceita o certificado confirmado pelo responsável (ver ConfiancaCertificado)
        val confianca = ConfiancaCertificado(impressaoConfirmada)
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(confianca), null) }

        return OkHttpClient.Builder()
            .sslSocketFactory(tls.socketFactory, confianca)
            // O nome/IP não é conferido: o certificado fixado pela impressão digital já identifica o servidor
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val chave = chaveDispositivo()
                val original = chain.request()

                if (chave.isBlank()) {
                    Log.w(TAG, "Chave do coletor não configurada: ${original.method} ${original.url.encodedPath} vai sem autenticação")
                }

                val sessaoDoRegistro = original.header(HEADER_SESSAO)
                val request = original.newBuilder()
                    .apply { if (chave.isNotBlank()) header(HEADER_CHAVE, chave) }
                    // Versão instalada: o servidor mostra na tela Coletores quem está com o app desatualizado
                    .header(HEADER_VERSAO_APP, BuildConfig.VERSION_NAME)
                    .header(HEADER_VERSAO_CODIGO, BuildConfig.VERSION_CODE.toString())
                    .apply {
                        when {
                            sessaoDoRegistro == null -> sessaoAtual()?.let { header(HEADER_SESSAO, it) }
                            sessaoDoRegistro.isBlank() -> removeHeader(HEADER_SESSAO)
                        }
                    }
                    .build()

                val inicio = System.currentTimeMillis()
                val response = chain.proceed(request)

                Log.d(TAG, "${request.method} ${request.url.encodedPath} → ${response.code} (${System.currentTimeMillis() - inicio} ms)")

                response
            }
            .build()
    }
}
