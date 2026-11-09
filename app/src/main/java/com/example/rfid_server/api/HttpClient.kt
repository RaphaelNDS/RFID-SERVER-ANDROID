package com.example.rfid_server.api

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.example.rfid_server.AppConfig
import com.example.rfid_server.BuildConfig
import com.example.rfid_server.CofreSessoes
import com.example.rfid_server.SessaoAtual
import com.example.rfid_server.dados.BancoLocal
import com.example.rfid_server.erros.RegistroErros
import okhttp3.Headers
import okhttp3.OkHttpClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
 * - Confere o relógio do coletor com o do servidor (cabeçalho Date das respostas, ver [RelogioServidor]) e manda a
 *   hora do aparelho no header X-Relogio-Coletor.
 */
object HttpClient {

    const val HEADER_CHAVE = "X-Chave-Dispositivo"
    const val HEADER_SESSAO = "X-Sessao-Operador"
    const val HEADER_MOMENTO = "X-Momento"
    const val HEADER_VERSAO_APP = "X-Versao-App"
    const val HEADER_VERSAO_CODIGO = "X-Versao-Codigo"
    const val HEADER_RELOGIO_COLETOR = "X-Relogio-Coletor"

    private const val TAG = "HTTP_CLIENT"

    @Volatile
    private var client: OkHttpClient? = null

    fun get(context: Context): OkHttpClient =
        client ?: synchronized(this) {
            client ?: context.applicationContext.let { app ->
                criar(
                    sessaoAtual = { SessaoAtual.token(app) },
                    impressaoConfirmada = { AppConfig.getImpressaoDigital(app) },
                    resolverSessao = { CofreSessoes.get(app).token(it) },
                    registrarErro = { tipo, mensagem -> RegistroErros.registrar(app, tipo, mensagem) },
                    diagnostico = DiagnosticoColetor.doAparelho(app)::cabecalhos,
                    chaveDispositivo = { AppConfig.getChaveDispositivo(app) }
                )
            }.also { client = it }
        }

    // Chave, sessão e certificado confirmado são lidos a cada uso (lambdas): mudar nas Configurações vale na hora
    internal fun criar(
        sessaoAtual: () -> String? = { null },
        impressaoConfirmada: () -> String? = { null },
        /** Troca a referência guardada no banco local pelo token da sessão (ver CofreSessoes). */
        resolverSessao: (String) -> String = { it },
        /** Guarda um erro para o servidor (tipo, mensagem): hoje só o relógio do coletor muito diferente do servidor. */
        registrarErro: (String, String) -> Unit = { _, _ -> },
        /** Cabeçalhos de diagnóstico do coletor (fila, espaço, aparelho): ver [DiagnosticoColetor]. */
        diagnostico: () -> Map<String, String> = { emptyMap() },
        // Último parâmetro: os testes passam a chave como lambda final (HttpClient.criar { "chave" })
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
                } else if (!AppConfig.chaveValida(chave)) {
                    // Acento ou espaço especial (colado de outro lugar): o OkHttp recusa o header com exceção, que fechava o
                    // app nas chamadas das telas e derrubava toda passada da sincronização. Vai sem a chave e o servidor
                    // responde "chave inválida", que a tela mostra
                    Log.e(TAG, "Chave do coletor salva com caractere inválido (acento ou espaço especial): ${original.url.encodedPath} vai sem autenticação")
                }

                val sessaoDoRegistro = original.header(HEADER_SESSAO)
                val request = original.newBuilder()
                    .apply { if (chave.isNotBlank() && AppConfig.chaveValida(chave)) header(HEADER_CHAVE, chave) }
                    // Versão instalada: o servidor mostra na tela Coletores quem está com o app desatualizado
                    .header(HEADER_VERSAO_APP, BuildConfig.VERSION_NAME)
                    .header(HEADER_VERSAO_CODIGO, BuildConfig.VERSION_CODE.toString())
                    // Hora do aparelho: o servidor pode comparar com a dele (e pode ignorar)
                    .header(HEADER_RELOGIO_COLETOR, horaDoAparelho())
                    // Estado da fila e do aparelho: o servidor pode mostrar (e pode ignorar). Nunca impede a requisição
                    .apply {
                        try {
                            diagnostico().forEach { (nome, valor) -> header(nome, valor) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Cabeçalhos de diagnóstico do coletor não enviados nesta requisição", e)
                        }
                    }
                    .apply {
                        when {
                            sessaoDoRegistro == null -> sessaoAtual()?.let { header(HEADER_SESSAO, it) }
                            sessaoDoRegistro.isBlank() -> removeHeader(HEADER_SESSAO)
                            // Registro da fila: o banco guarda só a referência; o token sai do cofre aqui
                            else -> header(HEADER_SESSAO, resolverSessao(sessaoDoRegistro))
                        }
                    }
                    .build()

                val inicio = System.currentTimeMillis()
                val response = chain.proceed(request)
                val fim = System.currentTimeMillis()

                Log.d(TAG, "${request.method} ${request.url.encodedPath} → ${response.code} (${fim - inicio} ms)")

                // Confere o relógio do coletor com o do servidor (cabeçalho Date de qualquer resposta, até de erro)
                conferirRelogio(response.headers, inicio, fim, registrarErro)

                response
            }
            .build()
    }

    /** Mede o desvio do relógio pelo cabeçalho Date da resposta (ver [RelogioServidor]). Sem Date, não mede. */
    internal fun conferirRelogio(headers: Headers, inicio: Long, fim: Long, registrarErro: (String, String) -> Unit) {
        val dataServidor = runCatching { headers.getDate("Date")?.time }.getOrNull() ?: return
        RelogioServidor.registrarMedicao(RelogioServidor.calcularDesvio(dataServidor, inicio, fim), fim, registrarErro)
    }

    private fun horaDoAparelho(): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now(ZoneId.systemDefault()))
}

/** Fila do coletor: leituras + etiquetas de inventário + cadastros ainda não enviados, e a leitura pendente mais antiga. */
data class EstadoFila(val pendentes: Int, val leituraMaisAntigaEm: Long?)

/**
 * Momento (epoch ms) da última passada de sincronização que terminou em SINCRONIZADO, só na memória: depois de o
 * app reabrir, o cabeçalho X-Ultima-Sinc-Ok só volta na próxima passada completa. Quem alimenta é o Sincronizador
 * ([registrar]).
 */
object UltimaSincronizacaoOk {
    @Volatile
    var momento: Long? = null
        private set

    fun registrar(agora: Long = System.currentTimeMillis()) {
        momento = agora
    }

    internal fun esquecer() {
        momento = null
    }
}

/**
 * Cabeçalhos com o estado do coletor, mandados em toda requisição para o servidor ver como ele está sem
 * depender do operador: o servidor atual ignora.
 *  - X-Fila-Pendentes: leituras + etiquetas de inventário + cadastros esperando envio;
 *  - X-Fila-Mais-Antiga: hora (ISO-8601) da leitura pendente mais antiga (sem leitura pendente, não vai);
 *  - X-Ultima-Sinc-Ok: hora (ISO-8601) da última sincronização completa (ver [UltimaSincronizacaoOk]);
 *  - X-Espaco-Livre: MB livres na pasta do app;
 *  - X-Android e X-Modelo: versão do Android e modelo do aparelho.
 *
 * O interceptor roda nas threads do OkHttp a cada requisição: a fila e o espaço livre são lidos no máximo a cada
 * [validadeMs] (o banco não é consultado a cada envio de lote). Se a leitura falhar, vai o último valor conhecido
 * (ou nada).
 */
class DiagnosticoColetor(
    private val lerFila: () -> EstadoFila,
    private val espacoLivreMb: () -> Long,
    private val versaoAndroid: String,
    private val modelo: String,
    private val ultimaSincOk: () -> Long? = { UltimaSincronizacaoOk.momento },
    /** Relógio para a validade do cache (monotônico). */
    private val agoraMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val zona: ZoneId = ZoneId.systemDefault(),
    private val validadeMs: Long = VALIDADE_PADRAO_MS
) {
    private var fila: EstadoFila? = null
    private var espaco: Long? = null
    private var lidoEm: Long? = null

    fun cabecalhos(): Map<String, String> {
        val (filaAtual, espacoAtual) = atualizar()
        val cabecalhos = LinkedHashMap<String, String>()
        filaAtual?.let { f ->
            cabecalhos[HEADER_FILA_PENDENTES] = f.pendentes.toString()
            f.leituraMaisAntigaEm?.let { cabecalhos[HEADER_FILA_MAIS_ANTIGA] = iso(it) }
        }
        ultimaSincOk()?.let { cabecalhos[HEADER_ULTIMA_SINC_OK] = iso(it) }
        espacoAtual?.let { cabecalhos[HEADER_ESPACO_LIVRE] = it.toString() }
        ascii(versaoAndroid).takeIf { it.isNotEmpty() }?.let { cabecalhos[HEADER_ANDROID] = it }
        ascii(modelo).takeIf { it.isNotEmpty() }?.let { cabecalhos[HEADER_MODELO] = it }
        return cabecalhos
    }

    @Synchronized
    private fun atualizar(): Pair<EstadoFila?, Long?> {
        val agora = agoraMs()
        val ultima = lidoEm
        if (ultima == null || agora - ultima >= validadeMs) {
            // Marca antes de ler: com o banco com defeito, tenta de novo só depois da validade (sem insistir a cada envio)
            lidoEm = agora
            try {
                fila = lerFila()
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível contar a fila do coletor para o servidor: vai o último valor conhecido", e)
            }
            try {
                espaco = espacoLivreMb()
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível medir o espaço livre do coletor", e)
            }
        }
        return fila to espaco
    }

    private fun iso(epochMs: Long): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(epochMs).atZone(zona).toOffsetDateTime())

    /** O OkHttp recusa cabeçalho com acento ou caractere de controle: o que não for ASCII visível vira "?". */
    private fun ascii(texto: String): String =
        texto.trim().map { if (it in ' '..'~') it else '?' }.joinToString("")

    companion object {
        private const val TAG = "HTTP_CLIENT"
        const val HEADER_FILA_PENDENTES = "X-Fila-Pendentes"
        const val HEADER_FILA_MAIS_ANTIGA = "X-Fila-Mais-Antiga"
        const val HEADER_ULTIMA_SINC_OK = "X-Ultima-Sinc-Ok"
        const val HEADER_ESPACO_LIVRE = "X-Espaco-Livre"
        const val HEADER_ANDROID = "X-Android"
        const val HEADER_MODELO = "X-Modelo"

        /** A fila e o espaço livre são lidos de novo no máximo a cada 30 s. */
        const val VALIDADE_PADRAO_MS = 30_000L

        /** Em produção: contagens novas dos DAOs (sem LiveData), espaço da pasta do app e dados do aparelho. */
        fun doAparelho(context: Context): DiagnosticoColetor {
            val app = context.applicationContext
            return DiagnosticoColetor(
                lerFila = {
                    val banco = BancoLocal.get(app)
                    EstadoFila(
                        pendentes = banco.leituraDao().contarPendentesAgora() +
                            banco.inventarioDao().contarItensNaoEnviadosAgora() +
                            banco.cadastroDao().contarPendentesAgora(),
                        leituraMaisAntigaEm = banco.leituraDao().lidoEmPendenteMaisAntiga()
                    )
                },
                espacoLivreMb = { StatFs(app.filesDir.path).availableBytes / (1024 * 1024) },
                versaoAndroid = Build.VERSION.RELEASE.orEmpty(),
                modelo = Build.MODEL.orEmpty()
            )
        }
    }
}
