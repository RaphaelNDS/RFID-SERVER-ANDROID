package com.example.rfid_server.service

import android.content.Context
import android.util.Log
import com.example.rfid_server.AppConfig
import com.example.rfid_server.SessaoOperador
import com.example.rfid_server.api.HttpClient
import com.example.rfid_server.api.CertificadoDiferenteException
import com.example.rfid_server.api.CertificadoNaoConfirmadoException
import com.example.rfid_server.api.ConfiancaCertificado
import com.example.rfid_server.dados.CadastroEntity
import com.example.rfid_server.dados.CatalogoColetor
import com.example.rfid_server.dados.EsperadoLocal
import com.example.rfid_server.dados.VersaoPublicada
import com.example.rfid_server.erros.MascaraSensivel
import com.example.rfid_server.view.TagView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** Resultado de uma chamada ao servidor: separa "não existe" de "não deu para perguntar". */
sealed class RespostaServidor<out T> {
    data class Sucesso<T>(val valor: T) : RespostaServidor<T>()
    object NaoEncontrado : RespostaServidor<Nothing>()
    /**
     * [motivoSessao]: só na recusa de sessão (SESSAO_INVALIDA), o "motivo" que o servidor manda (DESCONHECIDA,
     * DESATIVADO, EXPIRADA...). null em servidor de antes desse campo.
     */
    data class Falha(val motivo: MotivoFalha, val mensagem: String, val motivoSessao: String? = null) : RespostaServidor<Nothing>()
}

enum class MotivoFalha {
    /** Sem rede, rede lenta (tempo esgotado) ou o endereço não leva a lugar nenhum. */
    SEM_CONEXAO,
    /**
     * A conexão foi recusada na hora (porta fechada): a rede chega até a máquina do servidor, mas ele não está rodando
     * (parado, reiniciando para uma versão nova). Tratada como SEM_CONEXAO na sincronização (para tudo e tenta de novo),
     * só com outra mensagem: o operador sabe que não é problema do coletor.
     */
    SERVIDOR_FORA_DO_AR,
    CHAVE_INVALIDA,
    ENDERECO_INVALIDO,
    /** Erro 5xx, redirecionamento ou resposta que não é o JSON esperado: vale tentar de novo depois. */
    ERRO_SERVIDOR,
    /** O servidor recusou ESTA requisição (4xx): repetir não adianta. */
    REQUISICAO_RECUSADA,
    /** A sessão do operador não vale para esta ação (expirada, encerrada, de outro coletor, usuário excluído). */
    SESSAO_INVALIDA,
    /** O certificado HTTPS do servidor não foi confirmado neste coletor, ou é diferente do confirmado. */
    CERTIFICADO,
    /**
     * HTTP 429: o servidor bloqueou o coletor por muitas tentativas com a chave inválida. Repetir só mantém o bloqueio:
     * quem resolve é o responsável (chave certa nas Configurações), e a sincronização para até lá.
     */
    MUITAS_TENTATIVAS,
    /**
     * Só da sincronização (não vem do servidor): registros retidos porque o servidor recusou a sessão, mas nenhuma das
     * sessões retidas é a sessão atual do aparelho (sessões anteriores ou de outro operador). Entrar de novo não muda
     * nada: eles esperam o prazo de 24 h ou o servidor voltar a aceitar.
     */
    SESSAO_ANTERIOR_RETIDA
}

/**
 * Quem fez e quando, para os envios da fila: o servidor confere se a sessão valia NAQUELE momento
 * (a ação pode ter sido feita offline, horas antes). [sessao] null = registro de antes do login de operador.
 */
data class Autoria(val sessao: String?, val momento: Long)

/**
 * Resposta de GET /api/android/inventarios/esperado. [esperado] null = 304: a lista guardada com o ETag enviado ainda
 * é a atual. [etag] null = servidor anterior à 1.4.4 (sem ETag): a lista vem inteira sempre, como antes.
 */
data class EsperadoBaixado(val esperado: EsperadoLocal?, val etag: String?)

/** Resposta do envio das justificativas: [invalidas] = tags recusadas de vez (tag ou motivo inválido). */
data class ResultadoJustificativas(val gravadas: Int, val invalidas: List<String>)

/** Histórico do bem na ficha (campos opcionais do servidor; datas em ISO-8601). */
data class HistoricoBem(
    val ultimoLocal: String?,
    val ultimoInventarioEm: String?,
    val ultimaLeituraEm: String?,
    val ultimaLeituraPor: String?,
    // Servidor 1.6.0 (gestão de patrimônio): situação do bem no cadastro (código e rótulo pronto para mostrar),
    // unidade, local e responsável atuais. Servidor mais antigo não manda: ficam null
    val situacao: String? = null,
    val situacaoRotulo: String? = null,
    val unidade: String? = null,
    val local: String? = null,
    val responsavel: String? = null
) {
    val vazio: Boolean
        get() = listOf(ultimoLocal, ultimoInventarioEm, ultimaLeituraEm, situacao, situacaoRotulo, unidade, local, responsavel)
            .all { it == null }
}

/** Resultado de uma leitura enviada em lote (POST /api/android/leituras/lote). */
enum class StatusLote {
    CADASTRADA,
    NAO_CADASTRADA,
    /** Recusa definitiva desta leitura: não adianta reenviar. */
    RECUSADA,
    /**
     * Status que este app não conhece (servidor mais novo), numa resposta 2xx: o servidor registrou a leitura,
     * então ela sai da fila. Não vem do servidor com esse nome.
     */
    ACEITA,
    /** Falha passageira no servidor: tenta de novo depois. */
    ERRO
}

/**
 * [indice] = posição da leitura no lote enviado; [tag] vem preenchida quando é CADASTRADA.
 * [motivoSessao]: em RECUSADA por causa da sessão do operador, o "motivo" do servidor (null nas outras recusas
 * e em servidor de antes desse campo).
 */
data class ResultadoLote(
    val indice: Int,
    val status: StatusLote,
    val tag: TagView?,
    val mensagem: String?,
    val motivoSessao: String? = null
)

enum class StatusConexao(val descricao: String) {
    CONECTADO("conectado"),
    CHAVE_INVALIDA("chave do coletor inválida ou não configurada"),
    ENDERECO_INVALIDO("endereço do servidor inválido"),
    SEM_CONEXAO("sem conexão (o coletor está na rede?)"),
    /** A máquina do servidor recusou a conexão na hora: a porta está fechada (servidor parado ou reiniciando). */
    SERVIDOR_FORA_DO_AR("porta fechada neste endereço: confira o IP e a porta, ou se o servidor está rodando"),
    RESPOSTA_INESPERADA("resposta inesperada (servidor desatualizado?)"),
    /** HTTP 429: o servidor bloqueou este coletor por um tempo depois de muitas tentativas com chave inválida. */
    MUITAS_TENTATIVAS("muitas tentativas com chave inválida: aguarde alguns minutos e confira a chave"),
    CERTIFICADO_NAO_CONFIRMADO("certificado do servidor ainda não confirmado"),
    CERTIFICADO_DIFERENTE("certificado do servidor DIFERENTE do confirmado")
}

/** Resultado do teste de conexão; nos casos de certificado, traz a impressão digital que o servidor apresentou. */
data class ResultadoConexao(val status: StatusConexao, val impressaoDigital: String? = null)

/**
 * Chamadas do app ao servidor. Tudo vai para /api/android, autenticado pela chave do
 * coletor (ver HttpClient).
 *
 * - Métodos com callback: assíncronos, o callback roda fora da thread principal.
 * - Métodos "...Agora": bloqueantes, para o Sincronizador (que já roda em segundo plano).
 *   Nunca chame da thread principal.
 */
class TagService internal constructor(
    private val client: OkHttpClient,
    private val baseUrl: () -> String
) {

    // Usado pelas telas. O construtor interno existe para os testes passarem um servidor falso
    constructor(context: Context) : this(HttpClient.get(context), { AppConfig.getBaseUrl(context) })

    private val json = "application/json".toMediaType()

    /**
     * Só para o lote de leituras: o servidor classifica e grava até 100 leituras antes de responder, e os 10 s
     * do cliente normal podem estourar num servidor ocupado. Estourando, o lote volta para a fila e vai de novo
     * (o servidor ainda não tem como reconhecer o reenvio). Mesmo cliente (chave, certificado, conexões), só com mais paciência.
     */
    internal val clienteLote: OkHttpClient by lazy { client.newBuilder().readTimeout(LEITURA_LOTE_S, TimeUnit.SECONDS).build() }

    fun testarConexao(callback: (ResultadoConexao) -> Unit) {

        val url = url("ping") ?: return callback(ResultadoConexao(StatusConexao.ENDERECO_INVALIDO))

        client.newCall(Request.Builder().url(url).get().build()).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                when (val recusa = ConfiancaCertificado.recusaNaCausa(e)) {
                    is CertificadoNaoConfirmadoException ->
                        callback(ResultadoConexao(StatusConexao.CERTIFICADO_NAO_CONFIRMADO, recusa.impressaoDigital))

                    is CertificadoDiferenteException ->
                        callback(ResultadoConexao(StatusConexao.CERTIFICADO_DIFERENTE, recusa.impressaoDigital))

                    else -> {
                        Log.w(TAG, "Servidor inacessível em ${url.host}:${url.port}: ${e.message}")
                        callback(ResultadoConexao(if (servidorRecusou(e)) StatusConexao.SERVIDOR_FORA_DO_AR else StatusConexao.SEM_CONEXAO))
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val status = when {
                        it.isSuccessful -> StatusConexao.CONECTADO
                        it.code == 401 || it.code == 403 -> StatusConexao.CHAVE_INVALIDA
                        it.code == 429 -> StatusConexao.MUITAS_TENTATIVAS
                        else -> StatusConexao.RESPOSTA_INESPERADA
                    }

                    if (status == StatusConexao.CONECTADO) {
                        Log.i(TAG, "Servidor conectado em ${url.host}:${url.port}")
                    } else {
                        Log.w(TAG, "Teste de conexão: ${status.descricao} (HTTP ${it.code})")
                    }

                    callback(ResultadoConexao(status))
                }
            }
        })
    }

    fun buscarTagAgora(epc: String, autoria: Autoria? = null): RespostaServidor<TagView> =
        consultarAgora(url("tags", epc), "buscar a tag $epc", autoria) { json -> tagDoJson(epc, json) }

    fun buscarTagPorPatrimonio(patrimonio: String, callback: (RespostaServidor<String>) -> Unit) {

        // Patrimônio no parâmetro (?valor=, servidor 1.4.3 em diante), não no caminho: o Tomcat recusa
        // "/" codificado (%2F) no caminho e patrimônios como 0123/2019 davam 400
        val url = url("tags", "patrimonio")?.newBuilder()?.addQueryParameter("valor", patrimonio)?.build()
            ?: return callback(falhaEndereco())
        val descricao = "buscar o patrimônio $patrimonio"

        enfileirar(Request.Builder().url(url).get().build(), descricao, callback) {
            interpretarConsulta(it, descricao) { json -> json.getString("epc") }
        }
    }

    /** Registra no servidor a leitura de uma tag cadastrada, com a hora em que o coletor a leu. */
    fun enviarLeituraAgora(epc: String, lidoEm: Long, sessao: String? = null): RespostaServidor<Unit> {
        val corpo = JSONObject()
            .put("tag", epc)
            .put("lidoEm", formatarDataHora(lidoEm))

        return enviarAgora(listOf("leitura"), corpo, "a leitura da tag $epc", autoria = Autoria(sessao, lidoEm))
    }

    fun enviarNaoCadastradaAgora(epc: String, autoria: Autoria? = null): RespostaServidor<Unit> =
        enviarAgora(listOf("naocadastrada"), JSONObject().put("tag", epc), "a tag não cadastrada $epc", autoria = autoria)

    /**
     * Várias leituras do MESMO operador ([sessao]) numa requisição só: o servidor classifica e registra cada uma
     * e devolve o resultado de cada uma (na ordem de [leituras]).
     * NaoEncontrado = servidor antigo, sem a rota de lote: use as rotas individuais.
     */
    fun enviarLoteAgora(leituras: List<Pair<String, Long>>, sessao: String): RespostaServidor<List<ResultadoLote>> {
        val url = url("leituras", "lote") ?: return falhaEndereco()
        val descricao = "o lote de ${leituras.size} leitura(s)"

        val itens = JSONArray()
        leituras.forEach { (epc, lidoEm) -> itens.put(JSONObject().put("tag", epc).put("lidoEm", formatarDataHora(lidoEm))) }

        // X-Momento = leitura mais recente; o servidor confere a hora de cada leitura item a item
        val autoria = Autoria(sessao, leituras.maxOf { it.second })
        val request = Request.Builder().url(url).comAutoria(autoria)
            .post(JSONObject().put("itens", itens).toString().toRequestBody(json)).build()

        return executar(request, "enviar $descricao", cliente = clienteLote) {
            when {
                it.code == 404 -> {
                    Log.w(TAG, "O servidor não conhece /api/android/leituras/lote (versão antiga): enviando uma leitura por vez")
                    RespostaServidor.NaoEncontrado
                }

                !it.isSuccessful -> falhaHttp(it, "enviar $descricao")

                else -> interpretarLote(it.body?.string().orEmpty(), leituras.size, descricao)
            }
        }
    }

    /**
     * Resposta do lote, item a item: um item estranho não derruba o lote inteiro (senão as 100 leituras voltariam
     * à fila e seriam reenviadas a cada passada, para sempre). Só um corpo que não é o JSON do lote vira falha.
     */
    private fun interpretarLote(corpo: String, quantidade: Int, descricao: String): RespostaServidor<List<ResultadoLote>> =
        try {
            val array = JSONObject(corpo).getJSONArray("resultados")
            val resultados = (0 until array.length()).mapNotNull { i -> resultadoDoLote(array.optJSONObject(i), i, quantidade, descricao) }

            if (resultados.size != quantidade) {
                Log.w(TAG, "Resposta ao enviar $descricao com ${resultados.size} resultado(s) para $quantidade leitura(s)")
            }
            RespostaServidor.Sucesso(resultados)
        } catch (e: JSONException) {
            Log.e(TAG, "Resposta inválida ao enviar $descricao. Esperado JSON, recebido: ${corpo.take(120)}", e)
            RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Resposta inválida do servidor")
        }

    /** Um item do lote, ou null se não der para saber a que leitura ele se refere (a leitura fica na fila). */
    private fun resultadoDoLote(item: JSONObject?, posicao: Int, quantidade: Int, descricao: String): ResultadoLote? {
        if (item == null) {
            Log.w(TAG, "Resposta ao enviar $descricao: resultado $posicao não é um objeto JSON, ignorado")
            return null
        }

        // Sem "indice", vale a posição (o servidor responde na ordem do envio)
        val indice = if (item.isNull("indice")) posicao else item.optInt("indice", -1)
        if (indice !in 0 until quantidade) {
            Log.w(TAG, "Resposta ao enviar $descricao: índice inválido (${item.opt("indice")}) no resultado $posicao, ignorado")
            return null
        }

        val nome = item.optStringOrNull("status")
        val status = StatusLote.values().firstOrNull { it.name == nome && it != StatusLote.ACEITA } ?: run {
            // A requisição deu 2xx, então o servidor registrou a leitura: tratar como ERRO a reenviaria para sempre
            Log.w(TAG, "Resposta ao enviar $descricao: status desconhecido '$nome' na leitura $indice; tratada como aceita")
            StatusLote.ACEITA
        }

        // Dados da tag que não são um objeto: a leitura é aceita sem eles (a tela os busca depois)
        val dados = item.optJSONObject("dados")?.let { tagDoJson(item.optStringOrNull("tag").orEmpty(), it) }
        // A mensagem do item recusado vai para a tela (Leituras recusadas): mesmo corte das outras mensagens do servidor
        val mensagem = item.optStringOrNull("mensagem")?.trim()?.takeIf { it.isNotEmpty() }?.let { limitarMensagem(it) }
        return ResultadoLote(indice, status, dados, mensagem, item.optStringOrNull("motivo"))
    }

    /**
     * Dados de uma tag cadastrada (consulta da tag e lote). Campos obrigatórios que faltarem ou vierem null ficam
     * vazios: no Android, getString de um null devolve o texto "null", e um campo faltando derrubaria a resposta toda.
     */
    /**
     * Histórico do bem para a ficha (GET /api/android/tags/{epc}): campos opcionais que o servidor pode mandar
     * (ultimoLocal, ultimoInventarioEm, ultimaLeituraEm, ultimaLeituraPor). Servidor que não manda = ficha só com o
     * cadastro. NaoEncontrado = etiqueta não cadastrada.
     */
    fun buscarHistorico(epc: String, callback: (RespostaServidor<HistoricoBem>) -> Unit) {
        val url = url("tags", epc) ?: return callback(falhaEndereco())
        val descricao = "buscar a ficha da tag $epc"
        enfileirar(Request.Builder().url(url).get().build(), descricao, callback) {
            interpretarConsulta(it, descricao) { json ->
                HistoricoBem(
                    ultimoLocal = json.optStringOrNull("ultimoLocal")?.ifBlank { null },
                    ultimoInventarioEm = json.optStringOrNull("ultimoInventarioEm")?.ifBlank { null },
                    ultimaLeituraEm = json.optStringOrNull("ultimaLeituraEm")?.ifBlank { null },
                    ultimaLeituraPor = json.optStringOrNull("ultimaLeituraPor")?.ifBlank { null },
                    situacao = json.optStringOrNull("situacao")?.ifBlank { null },
                    situacaoRotulo = json.optStringOrNull("situacaoRotulo")?.ifBlank { null },
                    unidade = json.optStringOrNull("unidade")?.ifBlank { null },
                    local = json.optStringOrNull("local")?.ifBlank { null },
                    responsavel = json.optStringOrNull("responsavel")?.ifBlank { null }
                )
            }
        }
    }

    /**
     * Justificativas dos não encontrados de um inventário (servidor 1.5.0): POST /api/android/inventarios/{id}/justificativas
     * com [itens] = [{"tag","motivo","observacao"?}], até 2000. Idempotente (o reenvio da mesma tag só atualiza).
     * [sessao] = quem justificou (referência do cofre ou token). NaoEncontrado = rota ou inventário que o servidor não
     * conhece (versão antiga, ou a abertura ainda não chegou): vale tentar de novo depois.
     */
    fun enviarJustificativasAgora(inventarioId: String, itens: JSONArray, sessao: String?): RespostaServidor<ResultadoJustificativas> {
        val url = url("inventarios", inventarioId, "justificativas") ?: return falhaEndereco()
        val descricao = "enviar ${itens.length()} justificativa(s) do inventário $inventarioId"
        val request = Request.Builder().url(url).header(HttpClient.HEADER_SESSAO, sessao.orEmpty())
            .post(itens.toString().toRequestBody(json)).build()
        return executar(request, descricao) { response ->
            when {
                response.code == 404 -> RespostaServidor.NaoEncontrado
                !response.isSuccessful -> falhaHttp(response, descricao)
                else -> {
                    val corpo = response.body?.string().orEmpty()
                    try {
                        val o = JSONObject(corpo)
                        val invalidas = o.optJSONArray("invalidas")
                        RespostaServidor.Sucesso(
                            ResultadoJustificativas(
                                gravadas = o.optInt("gravadas", 0),
                                invalidas = (0 until (invalidas?.length() ?: 0)).map { invalidas!!.getString(it) }
                            )
                        )
                    } catch (e: JSONException) {
                        Log.e(TAG, "Resposta inválida ao $descricao: ${corpo.take(120)}", e)
                        RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Resposta inválida do servidor")
                    }
                }
            }
        }
    }

    private fun tagDoJson(epc: String, json: JSONObject) = TagView(
        epc = epc,
        codigoInterno = json.optStringOrNull("codigoInterno").orEmpty(),
        tipo = json.optStringOrNull("tipo"),
        marca = json.optStringOrNull("marca"),
        modelo = json.optStringOrNull("modelo"),
        patrimonio = json.optStringOrNull("patrimonio").orEmpty(),
        numeroSerie = json.optStringOrNull("numeroSerie").orEmpty()
    )

    // ================= CADASTRO DE ETIQUETA =================

    /** JSON do catálogo (tipo → marca → modelo + podeCadastrar), já conferido, para guardar no coletor. */
    fun buscarCatalogo(callback: (RespostaServidor<String>) -> Unit) {
        val url = url("catalogo") ?: return callback(falhaEndereco())
        val descricao = "buscar o catálogo"

        enfileirar(Request.Builder().url(url).get().build(), descricao, callback) {
            if (!it.isSuccessful) return@enfileirar falhaHttp(it, descricao)

            val corpo = it.body?.string().orEmpty()
            try {
                CatalogoColetor.deJson(corpo)
                RespostaServidor.Sucesso(corpo)
            } catch (e: JSONException) {
                Log.e(TAG, "Resposta inválida ao $descricao: ${corpo.take(120)}", e)
                RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Resposta inválida do servidor")
            }
        }
    }

    /**
     * Envia um cadastro feito no coletor. Recusas do servidor sobre ESTE cadastro (400 dados inválidos,
     * 403 coletor sem permissão, 409 etiqueta já cadastrada com outros dados) voltam como
     * REQUISICAO_RECUSADA com a mensagem do servidor, para mostrar ao operador.
     */
    fun cadastrarTagAgora(cadastro: CadastroEntity): RespostaServidor<Unit> {
        val descricao = "o cadastro da tag ${cadastro.epc}"

        val corpo = JSONObject()
            .put("tag", cadastro.epc)
            .put("codigoInterno", cadastro.codigoInterno)
            .put("patrimonio", cadastro.patrimonio)
            .put("numeroSerie", cadastro.numeroSerie)
            .put("tipoEquipamentoId", cadastro.tipoId ?: JSONObject.NULL)
            .put("marcaId", cadastro.marcaId ?: JSONObject.NULL)
            .put("modeloId", cadastro.modeloId ?: JSONObject.NULL)

        // Quem cadastrou e quando (o cadastro pode ter sido feito offline)
        return enviarAgora(
            listOf("tags"), corpo, descricao,
            autoria = Autoria(cadastro.sessaoOperador, cadastro.criadoEm),
            recusaComMensagem = { codigo -> "Servidor recusou o cadastro (HTTP $codigo)".takeIf { codigo in setOf(400, 403, 409) } }
        )
    }

    /** Campo "mensagem" do JSON de erro do servidor ({"status","mensagem",...}), se houver. */
    private fun mensagemDoServidor(response: Response): String? =
        try {
            JSONObject(response.body?.string().orEmpty()).optString("mensagem").trim().takeIf { it.isNotEmpty() }?.let { limitarMensagem(it) }
        } catch (e: JSONException) {
            null
        }

    // ================= LOGIN DO OPERADOR =================

    /** Confere email e senha no servidor e abre a sessão do operador neste coletor. Exige rede. */
    fun entrar(email: String, senha: String, callback: (RespostaServidor<SessaoOperador>) -> Unit) {
        val url = url("login") ?: return callback(falhaEndereco())
        val corpo = JSONObject().put("email", email).put("senha", senha)

        // Header de sessão em branco: o login não leva a sessão de quem estava logado antes
        val request = Request.Builder().url(url).header(HttpClient.HEADER_SESSAO, "")
            .post(corpo.toString().toRequestBody(json)).build()

        enfileirar(request, "entrar como ${MascaraSensivel.email(email)}", callback) {
            if (!it.isSuccessful) {
                // Senha errada, bloqueio por tentativas, sem permissão, chave do coletor: a mensagem vem do servidor
                val mensagem = mensagemDoServidor(it) ?: "Não foi possível entrar (HTTP ${it.code})"
                Log.w(TAG, "Login de ${MascaraSensivel.email(email)} recusado pelo servidor (HTTP ${it.code}): $mensagem")
                return@enfileirar RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, mensagem)
            }

            val texto = it.body?.string().orEmpty()
            try {
                RespostaServidor.Sucesso(SessaoOperador.deJson(texto))
            } catch (e: Exception) {
                Log.e(TAG, "Resposta inválida ao entrar: ${texto.take(80)}", e)
                RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Resposta inválida do servidor")
            }
        }
    }

    /** Encerra a sessão no servidor. Sem rede, a sessão expira sozinha no prazo; o app sai de qualquer jeito. */
    fun sair(token: String) {
        val url = url("logout") ?: return
        val request = Request.Builder().url(url).header(HttpClient.HEADER_SESSAO, token)
            .post("{}".toRequestBody(json)).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "Logout não chegou ao servidor (${e.message}): a sessão expira sozinha no prazo")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { Log.i(TAG, "Logout registrado no servidor (HTTP ${it.code})") }
            }
        })
    }

    /**
     * "Esqueci o PIN" das Configurações: um ADMIN confirma com email e senha (sem abrir sessão nem derrubar a do
     * operador). Sucesso = pode apagar o PIN. A recusa traz a mensagem do servidor para mostrar. Exige rede.
     */
    fun liberarPin(email: String, senha: String, callback: (RespostaServidor<Unit>) -> Unit) {
        // Um segmento por parte: "pin/liberar" num segmento só vira pin%2Fliberar, que o Tomcat recusa com 400
        val url = url("pin", "liberar") ?: return callback(falhaEndereco())
        val corpo = JSONObject().put("email", email).put("senha", senha)

        val request = Request.Builder().url(url).header(HttpClient.HEADER_SESSAO, "")
            .post(corpo.toString().toRequestBody(json)).build()

        enfileirar(request, "liberar o PIN com ${MascaraSensivel.email(email)}", callback) {
            when {
                it.isSuccessful -> {
                    Log.w(TAG, "PIN das Configurações liberado pelo administrador ${MascaraSensivel.email(email)}")
                    RespostaServidor.Sucesso(Unit)
                }

                it.code == 404 -> {
                    Log.e(TAG, "O servidor não conhece /api/android/pin/liberar: servidor desatualizado?")
                    RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Servidor desatualizado: atualize o servidor para liberar o PIN")
                }

                else -> {
                    // Senha errada, não é ADMIN, bloqueio por tentativas, chave do coletor: a mensagem vem do servidor
                    val mensagem = mensagemDoServidor(it) ?: "Não foi possível liberar o PIN (HTTP ${it.code})"
                    Log.w(TAG, "Liberação do PIN com ${MascaraSensivel.email(email)} recusada pelo servidor (HTTP ${it.code}): $mensagem")
                    RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, mensagem)
                }
            }
        }
    }

    /**
     * "Esqueceu a senha?": pede uma senha nova ao administrador. O servidor responde igual exista ou não o
     * email; o [callback] recebe a mensagem para mostrar ao operador. Exige rede.
     */
    fun esqueciSenha(email: String, callback: (RespostaServidor<String>) -> Unit) {
        val url = url("esqueci-senha") ?: return callback(falhaEndereco())
        val corpo = JSONObject().put("email", email)

        // Ninguém está logado na tela de login: o pedido vai sem sessão de operador
        val request = Request.Builder().url(url).header(HttpClient.HEADER_SESSAO, "")
            .post(corpo.toString().toRequestBody(json)).build()

        enfileirar(request, "pedir nova senha para ${MascaraSensivel.email(email)}", callback) {
            when {
                it.isSuccessful -> {
                    Log.i(TAG, "Pedido de nova senha para ${MascaraSensivel.email(email)} enviado ao administrador")
                    RespostaServidor.Sucesso(mensagemDoServidor(it) ?: "Pedido enviado ao administrador.")
                }

                it.code == 404 -> {
                    Log.e(TAG, "O servidor não conhece /api/android/esqueci-senha: servidor desatualizado?")
                    RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Servidor desatualizado: peça a nova senha ao administrador")
                }

                else -> falhaHttp(it, "pedir nova senha")
            }
        }
    }

    // ================= ERROS DO APP =================

    /**
     * Envia ao servidor um lote de erros do app (FilaErros). NaoEncontrado = servidor sem essa rota. Sem operador:
     * o erro pode ter acontecido antes do login. Bloqueante.
     */
    fun enviarErrosAgora(itens: JSONArray): RespostaServidor<Unit> {
        val url = url("erros") ?: return falhaEndereco()
        val request = Request.Builder().url(url).header(HttpClient.HEADER_SESSAO, "")
            .post(JSONObject().put("itens", itens).toString().toRequestBody(json)).build()

        return executar(request, "enviar os erros do app") {
            when {
                it.isSuccessful -> RespostaServidor.Sucesso(Unit)
                it.code == 404 -> RespostaServidor.NaoEncontrado
                else -> falhaHttp(it, "enviar ${itens.length()} erro(s) do app")
            }
        }
    }

    // ================= ATUALIZAÇÃO DO APP =================

    /** Versão publicada no servidor. NaoEncontrado = nenhuma publicada (ou servidor sem essa função). Sem operador. */
    fun consultarAtualizacao(callback: (RespostaServidor<VersaoPublicada>) -> Unit) {
        val url = url("atualizacao") ?: return callback(falhaEndereco())
        val descricao = "consultar a versão publicada do app"

        // Header de sessão em branco: a consulta acontece na tela de login, sem operador
        val request = Request.Builder().url(url).header(HttpClient.HEADER_SESSAO, "").get().build()

        enfileirar(request, descricao, callback) {
            when {
                it.code == 204 || it.code == 404 -> RespostaServidor.NaoEncontrado
                !it.isSuccessful -> falhaHttp(it, descricao)
                else -> {
                    val corpo = it.body?.string().orEmpty()
                    try {
                        RespostaServidor.Sucesso(VersaoPublicada.deJson(corpo))
                    } catch (e: Exception) {
                        Log.e(TAG, "Resposta inválida ao $descricao: ${corpo.take(120)}", e)
                        RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Resposta inválida do servidor")
                    }
                }
            }
        }
    }

    /**
     * Baixa o APK da [versao] para [destino] e confere o SHA-256 antes de devolver. Arquivo truncado ou
     * diferente é apagado e volta como falha. [progresso] recebe os bytes já baixados. Bloqueante.
     */
    fun baixarAtualizacaoAgora(versao: VersaoPublicada, destino: File, progresso: (Long) -> Unit = {}): RespostaServidor<File> {
        val url = url("atualizacao", "apk") ?: return falhaEndereco()
        val descricao = "baixar a versão ${versao.versaoNome} do app"
        val request = Request.Builder().url(url).header(HttpClient.HEADER_SESSAO, "").get().build()

        return try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "A versão não está mais disponível no servidor")
                    !response.isSuccessful -> falhaHttp(response, descricao)
                    versaoTrocada(response, versao) -> {
                        val servido = response.header(HttpClient.HEADER_VERSAO_CODIGO)
                        Log.w(TAG, "O servidor entregou o APK do código $servido no lugar do ${versao.codigo} (${versao.versaoNome}): outra versão foi publicada no meio; download não feito")
                        RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, MENSAGEM_VERSAO_TROCADA)
                    }
                    else -> salvarConferindo(response, versao, destino, progresso)
                }
            }
        } catch (e: IOException) {
            destino.delete()
            falhaConexao(descricao, e)
        }
    }

    // Servidor sem o header (versão antiga) não é recusado: o SHA-256 confere o arquivo do mesmo jeito
    private fun versaoTrocada(response: Response, versao: VersaoPublicada): Boolean {
        val servido = response.header(HttpClient.HEADER_VERSAO_CODIGO)?.trim()?.toIntOrNull() ?: return false
        return servido != versao.codigo
    }

    private fun salvarConferindo(response: Response, versao: VersaoPublicada, destino: File, progresso: (Long) -> Unit): RespostaServidor<File> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var grandeDemais = false

        destino.parentFile?.mkdirs()
        response.body!!.byteStream().use { entrada ->
            destino.outputStream().use { saida ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val lidos = entrada.read(buffer)
                    if (lidos < 0) break
                    total += lidos
                    // Maior que o publicado já não confere: para na hora, sem encher o armazenamento do coletor
                    if (total > versao.tamanho) {
                        grandeDemais = true
                        break
                    }
                    saida.write(buffer, 0, lidos)
                    digest.update(buffer, 0, lidos)
                    progresso(total)
                }
            }
        }

        if (grandeDemais) {
            destino.delete()
            Log.e(TAG, "APK da versão ${versao.versaoNome} maior que o publicado (mais de ${versao.tamanho} bytes): download interrompido e descartado")
            return RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "O arquivo baixado não confere (maior que o esperado). Tente de novo")
        }

        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != versao.sha256 || total != versao.tamanho) {
            destino.delete()
            Log.e(TAG, "APK da versão ${versao.versaoNome} baixado não confere ($total bytes, SHA-256 $sha; esperado ${versao.tamanho} bytes, ${versao.sha256}): descartado")
            return RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "O arquivo baixado não confere (incompleto ou corrompido). Tente de novo")
        }

        Log.i(TAG, "APK da versão ${versao.versaoNome} (código ${versao.codigo}) baixado e conferido: $total bytes, SHA-256 confere")
        return RespostaServidor.Sucesso(destino)
    }

    // ================= INVENTÁRIO POR LOCAL =================

    /** Locais já usados em inventários (sugestões na tela do inventário). */
    fun listarLocais(callback: (RespostaServidor<List<String>>) -> Unit) {
        val url = url("locais") ?: return callback(falhaEndereco())
        val descricao = "buscar a lista de locais"

        enfileirar(Request.Builder().url(url).get().build(), descricao, callback) { interpretarLista(it, descricao) }
    }

    /**
     * O que é esperado no [local] (o que foi encontrado no último inventário finalizado dele).
     * NaoEncontrado = servidor de uma versão sem essa rota: o inventário segue sem a lista.
     */
    fun buscarEsperado(local: String, etagGuardado: String? = null, callback: (RespostaServidor<EsperadoBaixado>) -> Unit) {
        val url = url("inventarios", "esperado")?.newBuilder()?.addQueryParameter("local", local)?.build()
            ?: return callback(falhaEndereco())
        val descricao = "buscar o esperado do local '$local'"

        // Com o ETag da lista guardada, o servidor 1.4.4+ responde 304 sem corpo quando nada mudou: com milhares de
        // etiquetas, a tela do inventário não baixa a lista inteira toda vez que abre. Servidor antigo ignora o header
        val request = Request.Builder().url(url).get()
            .apply { if (!etagGuardado.isNullOrBlank()) header("If-None-Match", etagGuardado) }
            .build()

        enfileirar(request, descricao, callback) { response ->
            if (response.code == 304) {
                RespostaServidor.Sucesso(EsperadoBaixado(esperado = null, etag = response.header("ETag") ?: etagGuardado))
            } else {
                val etag = response.header("ETag")?.trim()?.ifEmpty { null }
                interpretarConsulta(response, descricao) { json -> EsperadoBaixado(EsperadoLocal.deJson(json.toString()), etag) }
            }
        }
    }

    fun abrirInventarioAgora(id: String, local: String, iniciadoEm: Long, sessao: String? = null): RespostaServidor<Unit> {
        val corpo = JSONObject()
            .put("id", id)
            .put("local", local)
            .put("iniciadoEm", formatarDataHora(iniciadoEm))

        return enviarAgora(listOf("inventarios"), corpo, "a abertura do inventário $id", autoria = Autoria(sessao, iniciadoEm))
    }

    /**
     * Envia etiquetas lidas no inventário ([epc] → lidoEm). NaoEncontrado = o servidor não conhece
     * este inventário (ex.: banco do servidor restaurado): o app precisa abrir de novo.
     */
    fun enviarItensInventarioAgora(id: String, itens: List<Pair<String, Long>>, sessao: String? = null): RespostaServidor<Unit> {
        val lista = JSONArray()
        itens.forEach { (epc, lidoEm) ->
            lista.put(JSONObject().put("tag", epc).put("lidoEm", formatarDataHora(lidoEm)))
        }

        return enviarAgora(
            listOf("inventarios", id, "itens"), JSONObject().put("itens", lista),
            "${itens.size} etiqueta(s) do inventário $id", naoEncontradoEhDoRecurso = true,
            // A sessão tem que valer na leitura mais recente do lote (todas são do mesmo operador)
            autoria = Autoria(sessao, itens.maxOfOrNull { it.second } ?: System.currentTimeMillis())
        )
    }

    fun finalizarInventarioAgora(id: String, finalizadoEm: Long, sessao: String? = null): RespostaServidor<Unit> =
        enviarAgora(
            listOf("inventarios", id, "finalizar"), JSONObject().put("finalizadoEm", formatarDataHora(finalizadoEm)),
            "a finalização do inventário $id", naoEncontradoEhDoRecurso = true,
            autoria = Autoria(sessao, finalizadoEm)
        )

    // ================= INTERNOS =================

    private fun <T> consultarAgora(url: HttpUrl?, descricao: String, autoria: Autoria?, converter: (JSONObject) -> T): RespostaServidor<T> {
        if (url == null) return falhaEndereco()

        return executar(Request.Builder().url(url).comAutoria(autoria).get().build(), descricao) {
            interpretarConsulta(it, descricao, converter)
        }
    }

    /**
     * Executa [request] na hora (bloqueante) e entrega a resposta a [interpretar], que roda dentro de
     * `response.use` (o corpo é fechado depois). Falha de conexão, inclusive recusa do certificado, vira a
     * Falha de [falhaConexao], com o log padrão; [descricao] completa "Sem conexão ao ...".
     * [cliente]: o lote de leituras usa o [clienteLote], com mais tempo de espera.
     */
    private fun <T> executar(
        request: Request,
        descricao: String,
        cliente: OkHttpClient = client,
        interpretar: (Response) -> RespostaServidor<T>
    ): RespostaServidor<T> =
        try {
            cliente.newCall(request).execute().use(interpretar)
        } catch (e: IOException) {
            falhaConexao(descricao, e)
        }

    /**
     * Versão assíncrona de [executar]: o [callback] recebe, fora da thread principal, o resultado de
     * [interpretar] (dentro de `response.use`) ou a Falha de [falhaConexao] se não deu para falar com o servidor.
     */
    private fun <T> enfileirar(
        request: Request,
        descricao: String,
        callback: (RespostaServidor<T>) -> Unit,
        interpretar: (Response) -> RespostaServidor<T>
    ) {
        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                callback(falhaConexao(descricao, e))
            }

            override fun onResponse(call: Call, response: Response) {
                // A leitura do corpo também pode falhar (conexão caiu no meio). Sem tratar aqui o callback nunca era
                // chamado e a tela ficava esperando para sempre (ex.: Localizar parado em "Buscando...")
                val resultado = try {
                    response.use(interpretar)
                } catch (e: IOException) {
                    falhaConexao(descricao, e)
                } catch (e: RuntimeException) {
                    Log.e(TAG, "Resposta inesperada do servidor ao $descricao", e)
                    RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Resposta inválida do servidor ao $descricao")
                }
                callback(resultado)
            }
        })
    }

    private fun <T> interpretarConsulta(
        response: Response,
        descricao: String,
        converter: (JSONObject) -> T
    ): RespostaServidor<T> {

        if (response.code == 404) return RespostaServidor.NaoEncontrado
        if (!response.isSuccessful) return falhaHttp(response, descricao)

        val corpo = response.body?.string().orEmpty()

        return try {
            RespostaServidor.Sucesso(converter(JSONObject(corpo)))
        } catch (e: JSONException) {
            Log.e(TAG, "Resposta inválida ao $descricao. Esperado JSON, recebido: ${corpo.take(120)}", e)
            RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Resposta inválida do servidor")
        }
    }

    /**
     * POST em /api/android/<segmentos>. Por padrão, 404 significa endereço que não existe no servidor
     * (versão antiga). Com [naoEncontradoEhDoRecurso], 404 significa que o recurso da URL
     * (ex.: o inventário) não existe lá, e volta como NaoEncontrado.
     *
     * [recusaComMensagem]: para os códigos HTTP em que o servidor recusa ESTE envio com uma mensagem para o
     * operador, devolve o texto a usar se o corpo não trouxer "mensagem" (null = código tratado como de costume).
     * A recusa volta como REQUISICAO_RECUSADA.
     */
    private fun enviarAgora(
        segmentos: List<String>,
        corpo: JSONObject,
        descricao: String,
        naoEncontradoEhDoRecurso: Boolean = false,
        autoria: Autoria? = null,
        recusaComMensagem: (Int) -> String? = { null }
    ): RespostaServidor<Unit> {

        val url = url(*segmentos.toTypedArray()) ?: return falhaEndereco()
        val request = Request.Builder().url(url).comAutoria(autoria).post(corpo.toString().toRequestBody(json)).build()

        return executar(request, "enviar $descricao") {
            val recusaPadrao = if (it.isSuccessful) null else recusaComMensagem(it.code)
            when {
                it.isSuccessful -> {
                    Log.d(TAG, "Enviado(a) ao servidor: $descricao")
                    RespostaServidor.Sucesso(Unit)
                }

                recusaPadrao != null -> {
                    val mensagem = mensagemDoServidor(it) ?: recusaPadrao
                    Log.w(TAG, "Servidor recusou $descricao (HTTP ${it.code}): $mensagem")
                    RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, mensagem)
                }

                it.code == 404 && naoEncontradoEhDoRecurso -> {
                    Log.w(TAG, "O servidor não encontrou o recurso ao enviar $descricao")
                    RespostaServidor.NaoEncontrado
                }

                it.code == 404 -> {
                    Log.e(TAG, "O servidor não conhece /api/android/${segmentos.joinToString("/")} ao enviar $descricao: servidor desatualizado?")
                    RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Servidor desatualizado (HTTP 404)")
                }

                else -> falhaHttp(it, "enviar $descricao")
            }
        }
    }

    private fun interpretarLista(response: Response, descricao: String): RespostaServidor<List<String>> {
        if (!response.isSuccessful) return falhaHttp(response, descricao)

        val corpo = response.body?.string().orEmpty()
        return try {
            val array = JSONArray(corpo)
            RespostaServidor.Sucesso((0 until array.length()).map { array.getString(it) })
        } catch (e: JSONException) {
            Log.e(TAG, "Resposta inválida ao $descricao. Esperado lista JSON, recebido: ${corpo.take(120)}", e)
            RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Resposta inválida do servidor")
        }
    }

    private fun falhaHttp(response: Response, descricao: String): RespostaServidor.Falha {
        val codigo = response.code
        // Lê o corpo só nos 4xx: o servidor manda {"status","mensagem",...} com o motivo em português
        val erro = if (codigo in 400..499) corpoDeErro(response) else null
        val mensagemServidor = mensagemCurta(erro)

        return when (codigo) {
            // O servidor diferencia: 401 da chave do coletor (sem "codigo") e 401 da sessão do operador
            401 -> if (erro?.optString("codigo") == "SESSAO_OPERADOR") {
                // Mesmo corte de 300 caracteres das outras mensagens do servidor (vai para a tela)
                val mensagem = mensagemServidor ?: "Sessão do operador inválida"
                // Servidor mais novo diz o porquê (DESCONHECIDA, DESATIVADO, EXPIRADA...); o antigo não manda
                val motivoSessao = erro.optStringOrNull("motivo")
                Log.w(TAG, "Sessão do operador recusada ao $descricao: $mensagem" + (motivoSessao?.let { " (motivo $it)" } ?: ""))
                RespostaServidor.Falha(MotivoFalha.SESSAO_INVALIDA, mensagem, motivoSessao)
            } else {
                Log.e(TAG, "Servidor recusou a chave do coletor ao $descricao (HTTP 401)")
                RespostaServidor.Falha(MotivoFalha.CHAVE_INVALIDA, "Chave do coletor inválida: verifique as Configurações")
            }

            // Na API do coletor, 403 é falta de permissão do OPERADOR para esta ação (a chave errada dá 401)
            403 -> {
                val mensagem = mensagemServidor ?: "Seu usuário não tem permissão para esta ação"
                Log.w(TAG, "Operador sem permissão ao $descricao: $mensagem")
                RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, mensagem)
            }

            // Muitas tentativas com chave inválida: o servidor bloqueia o coletor. Motivo próprio: a sincronização para
            // (insistir mantém o bloqueio) e a tela pede o responsável, em vez de "Erro no servidor" com "Enviar agora"
            429 -> {
                val mensagem = mensagemServidor ?: "Muitas tentativas recusadas pelo servidor: aguarde alguns minutos (HTTP 429)"
                Log.w(TAG, "Servidor bloqueou o coletor por muitas tentativas ao $descricao (HTTP 429): $mensagem")
                RespostaServidor.Falha(MotivoFalha.MUITAS_TENTATIVAS, mensagem)
            }

            // Passageiros: tempo esgotado (408), cedo demais (425) e lote grande demais para um proxy no caminho
            // (413). Tratar como recusa definitiva descartava leituras que iriam na próxima
            408, 413, 425 -> {
                val mensagem = mensagemServidor ?: "Servidor ocupado ou indisponível agora (HTTP $codigo): nova tentativa depois"
                Log.w(TAG, "Servidor pediu para tentar depois ao $descricao (HTTP $codigo): $mensagem")
                RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, mensagem)
            }

            // 400, 409...: o servidor explica a recusa (ex.: patrimônio em várias etiquetas) e o operador precisa ler
            in 400..499 -> {
                val mensagem = mensagemServidor ?: "Servidor recusou a requisição (HTTP $codigo)"
                Log.e(TAG, "Servidor recusou a requisição ao $descricao (HTTP $codigo): $mensagem")
                RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, mensagem)
            }

            in 300..399 -> {
                Log.e(TAG, "Redirecionamento inesperado ao $descricao (HTTP $codigo): servidor exigindo login?")
                RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Resposta inesperada do servidor (HTTP $codigo)")
            }

            else -> {
                Log.e(TAG, "Erro do servidor ao $descricao: HTTP $codigo")
                RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Erro do servidor (HTTP $codigo)")
            }
        }
    }

    /** "mensagem" do JSON de erro, limitada a [MAX_MENSAGEM_SERVIDOR] caracteres (vai para uma tela pequena). */
    private fun mensagemCurta(erro: JSONObject?): String? =
        erro?.optString("mensagem")?.trim()?.takeIf { it.isNotEmpty() }?.let { limitarMensagem(it) }

    private fun corpoDeErro(response: Response): JSONObject? =
        try {
            JSONObject(response.body?.string().orEmpty())
        } catch (e: JSONException) {
            null
        }

    private fun falhaConexao(descricao: String, e: IOException): RespostaServidor.Falha {
        // Recusa do certificado HTTPS chega como falha de conexão (SSLHandshakeException): tem motivo próprio
        when (ConfiancaCertificado.recusaNaCausa(e)) {
            is CertificadoNaoConfirmadoException -> {
                Log.w(TAG, "Certificado do servidor não confirmado ao $descricao")
                return RespostaServidor.Falha(MotivoFalha.CERTIFICADO, "Certificado do servidor ainda não confirmado: abra as Configurações do coletor")
            }

            is CertificadoDiferenteException -> {
                Log.e(TAG, "Certificado do servidor diferente do confirmado ao $descricao: conexão recusada")
                return RespostaServidor.Falha(MotivoFalha.CERTIFICADO, "O certificado do servidor mudou: confira nas Configurações do coletor antes de continuar")
            }

            else -> Unit
        }

        if (servidorRecusou(e)) {
            Log.w(TAG, "Servidor não está respondendo (conexão recusada) ao $descricao: ${e.message}")
            return RespostaServidor.Falha(MotivoFalha.SERVIDOR_FORA_DO_AR, MENSAGEM_SERVIDOR_FORA_DO_AR)
        }
        Log.w(TAG, "Sem conexão ao $descricao: ${e.message}")
        return RespostaServidor.Falha(MotivoFalha.SEM_CONEXAO, "Sem conexão com o servidor")
    }

    /**
     * A conexão foi recusada na hora (ECONNREFUSED: a máquina responde, mas a porta está fechada)? É o servidor parado
     * ou reiniciando, e não falta de rede no coletor (ENETUNREACH, nome que não resolve, tempo esgotado). Em 09/11 o
     * servidor ficou 10 min parado para um deploy e o coletor dizia só "Sem conexão com o servidor".
     */
    private fun servidorRecusou(e: Throwable, profundidade: Int = 0, vistos: MutableSet<Throwable> = HashSet()): Boolean {
        if (profundidade > 8 || !vistos.add(e)) return false
        if (e is java.net.ConnectException) {
            val texto = e.message.orEmpty()
            if (texto.contains("ECONNREFUSED") || texto.contains("Connection refused", ignoreCase = true)) return true
        }
        // Causa e suprimidas: o OkHttp 5 guarda a falha de cada rota tentada como "suppressed" da última
        return listOfNotNull(e.cause).plus(e.suppressed).any { servidorRecusou(it, profundidade + 1, vistos) }
    }

    private fun falhaEndereco() =
        if (baseUrl().isBlank()) RespostaServidor.Falha(MotivoFalha.ENDERECO_INVALIDO, MENSAGEM_NAO_CONFIGURADO)
        else RespostaServidor.Falha(MotivoFalha.ENDERECO_INVALIDO, "Endereço do servidor inválido: verifique as Configurações")

    // Monta /api/android/<segmentos> codificando cada parte (EPC/patrimônio com "/" ou espaço não quebram a URL)
    private fun url(vararg segmentos: String): HttpUrl? {
        val endereco = baseUrl()
        if (endereco.isBlank()) {
            Log.w(TAG, "Servidor não configurado neste coletor: informe o IP nas Configurações do coletor")
            return null
        }
        val base = endereco.toHttpUrlOrNull() ?: run {
            Log.e(TAG, "Endereço do servidor inválido: $endereco")
            return null
        }

        return base.newBuilder()
            .addPathSegments("api/android")
            .apply { segmentos.forEach { addPathSegment(it) } }
            .build()
    }

    /**
     * Headers de autoria dos envios da fila: a sessão de quem fez (em branco = registro antigo, sem
     * operador; o HttpClient remove) e quando a ação aconteceu. Sem autoria (null), o HttpClient usa a sessão atual.
     */
    private fun Request.Builder.comAutoria(autoria: Autoria?): Request.Builder =
        if (autoria == null) this
        else header(HttpClient.HEADER_SESSAO, autoria.sessao.orEmpty())
            .header(HttpClient.HEADER_MOMENTO, formatarDataHora(autoria.momento))

    private fun JSONObject.optStringOrNull(nome: String): String? =
        if (isNull(nome)) null else optString(nome)

    companion object {
        private const val TAG = "TAG_SERVICE"

        /** Mesmo cliente (chave, certificado confirmado), mas falando com [endereco] — ex.: testar um IP ainda não salvo. */
        fun paraEndereco(context: Context, endereco: String) = TagService(HttpClient.get(context)) { endereco }

        const val MENSAGEM_SERVIDOR_FORA_DO_AR = "O servidor não está respondendo (parado ou reiniciando?). Tente de novo em alguns minutos"

        const val MENSAGEM_NAO_CONFIGURADO = "Servidor não configurado: informe o IP do servidor nas Configurações do coletor"

        const val MENSAGEM_VERSAO_TROCADA = "Outra versão do app foi publicada enquanto baixava. Toque em atualizar de novo"

        /** Espera pela resposta do lote de leituras (segundos). */
        internal const val LEITURA_LOTE_S = 30L

        /** Tamanho máximo da mensagem do servidor mostrada ao operador. */
        internal const val MAX_MENSAGEM_SERVIDOR = 300

        internal fun limitarMensagem(texto: String): String =
            if (texto.length <= MAX_MENSAGEM_SERVIDOR) texto else texto.take(MAX_MENSAGEM_SERVIDOR - 1).trimEnd() + "…"

        /** Epoch em ms → ISO-8601 com o fuso do aparelho (ex.: 2026-10-13T13:03:51.337-03:00). */
        internal fun formatarDataHora(epochMs: Long): String =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
            )
    }
}
