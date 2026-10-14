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

/** Resultado de uma chamada ao servidor: separa "não existe" de "não deu para perguntar". */
sealed class RespostaServidor<out T> {
    data class Sucesso<T>(val valor: T) : RespostaServidor<T>()
    object NaoEncontrado : RespostaServidor<Nothing>()
    data class Falha(val motivo: MotivoFalha, val mensagem: String) : RespostaServidor<Nothing>()
}

enum class MotivoFalha {
    SEM_CONEXAO,
    CHAVE_INVALIDA,
    ENDERECO_INVALIDO,
    /** Erro 5xx, redirecionamento ou resposta que não é o JSON esperado: vale tentar de novo depois. */
    ERRO_SERVIDOR,
    /** O servidor recusou ESTA requisição (4xx): repetir não adianta. */
    REQUISICAO_RECUSADA,
    /** A sessão do operador não vale para esta ação (expirada, encerrada, de outro coletor, usuário excluído). */
    SESSAO_INVALIDA,
    /** O certificado HTTPS do servidor não foi confirmado neste coletor, ou é diferente do confirmado. */
    CERTIFICADO
}

/**
 * Quem fez e quando, para os envios da fila: o servidor confere se a sessão valia NAQUELE momento
 * (a ação pode ter sido feita offline, horas antes). [sessao] null = registro de antes do login de operador.
 */
data class Autoria(val sessao: String?, val momento: Long)

/** Resultado de uma leitura enviada em lote (POST /api/android/leituras/lote). */
enum class StatusLote {
    CADASTRADA,
    NAO_CADASTRADA,
    /** Recusa definitiva desta leitura: não adianta reenviar. */
    RECUSADA,
    /** Falha passageira no servidor: tenta de novo depois. */
    ERRO
}

/** [indice] = posição da leitura no lote enviado; [tag] vem preenchida quando é CADASTRADA. */
data class ResultadoLote(val indice: Int, val status: StatusLote, val tag: TagView?, val mensagem: String?)

enum class StatusConexao(val descricao: String) {
    CONECTADO("conectado"),
    CHAVE_INVALIDA("chave do coletor inválida ou não configurada"),
    ENDERECO_INVALIDO("endereço do servidor inválido"),
    SEM_CONEXAO("sem conexão"),
    RESPOSTA_INESPERADA("resposta inesperada (servidor desatualizado?)"),
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
                        callback(ResultadoConexao(StatusConexao.SEM_CONEXAO))
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val status = when {
                        it.isSuccessful -> StatusConexao.CONECTADO
                        it.code == 401 || it.code == 403 -> StatusConexao.CHAVE_INVALIDA
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
        consultarAgora(url("tags", epc), "buscar a tag $epc", autoria) { json ->
            TagView(
                epc = epc,
                codigoInterno = json.getString("codigoInterno"),
                tipo = json.optStringOrNull("tipo"),
                marca = json.optStringOrNull("marca"),
                modelo = json.optStringOrNull("modelo"),
                patrimonio = json.getString("patrimonio"),
                numeroSerie = json.getString("numeroSerie")
            )
        }

    fun buscarTagPorPatrimonio(patrimonio: String, callback: (RespostaServidor<String>) -> Unit) {

        val url = url("tags", "patrimonio", patrimonio) ?: return callback(falhaEndereco())
        val descricao = "buscar o patrimônio $patrimonio"

        client.newCall(Request.Builder().url(url).get().build()).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                callback(falhaConexao(descricao, e))
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.use { interpretarConsulta(it, descricao) { json -> json.getString("epc") } })
            }
        })
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

        return try {
            client.newCall(request).execute().use {
                when {
                    it.code == 404 -> {
                        Log.w(TAG, "O servidor não conhece /api/android/leituras/lote (versão antiga): enviando uma leitura por vez")
                        RespostaServidor.NaoEncontrado
                    }

                    !it.isSuccessful -> falhaHttp(it, "enviar $descricao")

                    else -> interpretarLote(it.body?.string().orEmpty(), leituras.size, descricao)
                }
            }
        } catch (e: IOException) {
            falhaConexao("enviar $descricao", e)
        }
    }

    private fun interpretarLote(corpo: String, quantidade: Int, descricao: String): RespostaServidor<List<ResultadoLote>> =
        try {
            val array = JSONObject(corpo).getJSONArray("resultados")
            val resultados = (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                val status = StatusLote.values().firstOrNull { it.name == item.optString("status") } ?: StatusLote.ERRO
                val dados = item.optJSONObject("dados")?.let { d ->
                    TagView(
                        epc = item.optString("tag"),
                        codigoInterno = d.getString("codigoInterno"),
                        tipo = d.optStringOrNull("tipo"),
                        marca = d.optStringOrNull("marca"),
                        modelo = d.optStringOrNull("modelo"),
                        patrimonio = d.getString("patrimonio"),
                        numeroSerie = d.getString("numeroSerie")
                    )
                }
                ResultadoLote(item.getInt("indice"), status, dados, item.optStringOrNull("mensagem"))
            }

            if (resultados.size != quantidade) {
                Log.w(TAG, "Resposta ao enviar $descricao com ${resultados.size} resultado(s) para $quantidade leitura(s)")
            }
            RespostaServidor.Sucesso(resultados)
        } catch (e: JSONException) {
            Log.e(TAG, "Resposta inválida ao enviar $descricao. Esperado JSON, recebido: ${corpo.take(120)}", e)
            RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Resposta inválida do servidor")
        }

    // ================= CADASTRO DE ETIQUETA =================

    /** JSON do catálogo (tipo → marca → modelo + podeCadastrar), já conferido, para guardar no coletor. */
    fun buscarCatalogo(callback: (RespostaServidor<String>) -> Unit) {
        val url = url("catalogo") ?: return callback(falhaEndereco())
        val descricao = "buscar o catálogo"

        client.newCall(Request.Builder().url(url).get().build()).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                callback(falhaConexao(descricao, e))
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.use {
                    if (!it.isSuccessful) return@use falhaHttp(it, descricao)

                    val corpo = it.body?.string().orEmpty()
                    try {
                        CatalogoColetor.deJson(corpo)
                        RespostaServidor.Sucesso(corpo)
                    } catch (e: JSONException) {
                        Log.e(TAG, "Resposta inválida ao $descricao: ${corpo.take(120)}", e)
                        RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Resposta inválida do servidor")
                    }
                })
            }
        })
    }

    /**
     * Envia um cadastro feito no coletor. Recusas do servidor sobre ESTE cadastro (400 dados inválidos,
     * 403 coletor sem permissão, 409 etiqueta já cadastrada com outros dados) voltam como
     * REQUISICAO_RECUSADA com a mensagem do servidor, para mostrar ao operador.
     */
    fun cadastrarTagAgora(cadastro: CadastroEntity): RespostaServidor<Unit> {
        val url = url("tags") ?: return falhaEndereco()
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
        val autoria = Autoria(cadastro.sessaoOperador, cadastro.criadoEm)
        val request = Request.Builder().url(url).comAutoria(autoria).post(corpo.toString().toRequestBody(json)).build()

        return try {
            client.newCall(request).execute().use {
                when (it.code) {
                    in 200..299 -> {
                        Log.d(TAG, "Enviado ao servidor: $descricao")
                        RespostaServidor.Sucesso(Unit)
                    }

                    400, 403, 409 -> {
                        val mensagem = mensagemDoServidor(it) ?: "Servidor recusou o cadastro (HTTP ${it.code})"
                        Log.w(TAG, "Servidor recusou $descricao: $mensagem")
                        RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, mensagem)
                    }

                    404 -> {
                        Log.e(TAG, "O servidor não conhece /api/android/tags: servidor desatualizado?")
                        RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Servidor desatualizado (HTTP 404)")
                    }

                    else -> falhaHttp(it, "enviar $descricao")
                }
            }
        } catch (e: IOException) {
            falhaConexao("enviar $descricao", e)
        }
    }

    /** Campo "mensagem" do JSON de erro do servidor ({"status","mensagem",...}), se houver. */
    private fun mensagemDoServidor(response: Response): String? =
        try {
            JSONObject(response.body?.string().orEmpty()).optString("mensagem").takeIf { it.isNotBlank() }
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

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                callback(falhaConexao("entrar como $email", e))
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.use {
                    if (!it.isSuccessful) {
                        // Senha errada, bloqueio por tentativas, sem permissão, chave do coletor: a mensagem vem do servidor
                        val mensagem = mensagemDoServidor(it) ?: "Não foi possível entrar (HTTP ${it.code})"
                        Log.w(TAG, "Login de $email recusado pelo servidor (HTTP ${it.code}): $mensagem")
                        return@use RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, mensagem)
                    }

                    val texto = it.body?.string().orEmpty()
                    try {
                        RespostaServidor.Sucesso(SessaoOperador.deJson(texto))
                    } catch (e: Exception) {
                        Log.e(TAG, "Resposta inválida ao entrar: ${texto.take(80)}", e)
                        RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Resposta inválida do servidor")
                    }
                })
            }
        })
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
     * "Esqueceu a senha?": pede uma senha nova ao administrador. O servidor responde igual exista ou não o
     * email; o [callback] recebe a mensagem para mostrar ao operador. Exige rede.
     */
    fun esqueciSenha(email: String, callback: (RespostaServidor<String>) -> Unit) {
        val url = url("esqueci-senha") ?: return callback(falhaEndereco())
        val corpo = JSONObject().put("email", email)

        // Ninguém está logado na tela de login: o pedido vai sem sessão de operador
        val request = Request.Builder().url(url).header(HttpClient.HEADER_SESSAO, "")
            .post(corpo.toString().toRequestBody(json)).build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                callback(falhaConexao("pedir nova senha para $email", e))
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.use {
                    when {
                        it.isSuccessful -> {
                            Log.i(TAG, "Pedido de nova senha para $email enviado ao administrador")
                            RespostaServidor.Sucesso(mensagemDoServidor(it) ?: "Pedido enviado ao administrador.")
                        }

                        it.code == 404 -> {
                            Log.e(TAG, "O servidor não conhece /api/android/esqueci-senha: servidor desatualizado?")
                            RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Servidor desatualizado: peça a nova senha ao administrador")
                        }

                        else -> falhaHttp(it, "pedir nova senha")
                    }
                })
            }
        })
    }

    // ================= ATUALIZAÇÃO DO APP =================

    /** Versão publicada no servidor. NaoEncontrado = nenhuma publicada (ou servidor sem essa função). Sem operador. */
    fun consultarAtualizacao(callback: (RespostaServidor<VersaoPublicada>) -> Unit) {
        val url = url("atualizacao") ?: return callback(falhaEndereco())
        val descricao = "consultar a versão publicada do app"

        // Header de sessão em branco: a consulta acontece na tela de login, sem operador
        val request = Request.Builder().url(url).header(HttpClient.HEADER_SESSAO, "").get().build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                callback(falhaConexao(descricao, e))
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.use {
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
                })
            }
        })
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
                    else -> salvarConferindo(response, versao, destino, progresso)
                }
            }
        } catch (e: IOException) {
            destino.delete()
            falhaConexao(descricao, e)
        }
    }

    private fun salvarConferindo(response: Response, versao: VersaoPublicada, destino: File, progresso: (Long) -> Unit): RespostaServidor<File> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L

        destino.parentFile?.mkdirs()
        response.body!!.byteStream().use { entrada ->
            destino.outputStream().use { saida ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val lidos = entrada.read(buffer)
                    if (lidos < 0) break
                    saida.write(buffer, 0, lidos)
                    digest.update(buffer, 0, lidos)
                    total += lidos
                    progresso(total)
                }
            }
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

        client.newCall(Request.Builder().url(url).get().build()).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                callback(falhaConexao(descricao, e))
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.use { interpretarLista(it, descricao) })
            }
        })
    }

    /**
     * O que é esperado no [local] (o que foi encontrado no último inventário finalizado dele).
     * NaoEncontrado = servidor de uma versão sem essa rota: o inventário segue sem a lista.
     */
    fun buscarEsperado(local: String, callback: (RespostaServidor<EsperadoLocal>) -> Unit) {
        val url = url("inventarios", "esperado")?.newBuilder()?.addQueryParameter("local", local)?.build()
            ?: return callback(falhaEndereco())
        val descricao = "buscar o esperado do local '$local'"

        client.newCall(Request.Builder().url(url).get().build()).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                callback(falhaConexao(descricao, e))
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.use { interpretarConsulta(it, descricao) { json -> EsperadoLocal.deJson(json.toString()) } })
            }
        })
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

        return try {
            client.newCall(Request.Builder().url(url).comAutoria(autoria).get().build()).execute().use {
                interpretarConsulta(it, descricao, converter)
            }
        } catch (e: IOException) {
            falhaConexao(descricao, e)
        }
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
     */
    private fun enviarAgora(
        segmentos: List<String>,
        corpo: JSONObject,
        descricao: String,
        naoEncontradoEhDoRecurso: Boolean = false,
        autoria: Autoria? = null
    ): RespostaServidor<Unit> {

        val url = url(*segmentos.toTypedArray()) ?: return falhaEndereco()
        val request = Request.Builder().url(url).comAutoria(autoria).post(corpo.toString().toRequestBody(json)).build()

        return try {
            client.newCall(request).execute().use {
                when {
                    it.isSuccessful -> {
                        Log.d(TAG, "Enviado(a) ao servidor: $descricao")
                        RespostaServidor.Sucesso(Unit)
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
        } catch (e: IOException) {
            falhaConexao("enviar $descricao", e)
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
        // Lê o corpo só nos 401/403, que precisam dele para saber o motivo
        val erro = if (codigo == 401 || codigo == 403) corpoDeErro(response) else null

        return when (codigo) {
            // O servidor diferencia: 401 da chave do coletor (sem "codigo") e 401 da sessão do operador
            401 -> if (erro?.optString("codigo") == "SESSAO_OPERADOR") {
                val mensagem = erro.optString("mensagem").ifBlank { "Sessão do operador inválida" }
                Log.w(TAG, "Sessão do operador recusada ao $descricao: $mensagem")
                RespostaServidor.Falha(MotivoFalha.SESSAO_INVALIDA, mensagem)
            } else {
                Log.e(TAG, "Servidor recusou a chave do coletor ao $descricao (HTTP 401)")
                RespostaServidor.Falha(MotivoFalha.CHAVE_INVALIDA, "Chave do coletor inválida: verifique as Configurações")
            }

            // Na API do coletor, 403 é falta de permissão do OPERADOR para esta ação (a chave errada dá 401)
            403 -> {
                val mensagem = erro?.optString("mensagem")?.takeIf { it.isNotBlank() } ?: "Seu usuário não tem permissão para esta ação"
                Log.w(TAG, "Operador sem permissão ao $descricao: $mensagem")
                RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, mensagem)
            }

            in 400..499 -> {
                Log.e(TAG, "Servidor recusou a requisição ao $descricao (HTTP $codigo)")
                RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, "Servidor recusou a requisição (HTTP $codigo)")
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

        Log.w(TAG, "Sem conexão ao $descricao: ${e.message}")
        return RespostaServidor.Falha(MotivoFalha.SEM_CONEXAO, "Sem conexão com o servidor")
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

        const val MENSAGEM_NAO_CONFIGURADO = "Servidor não configurado: informe o IP do servidor nas Configurações do coletor"

        /** Epoch em ms → ISO-8601 com o fuso do aparelho (ex.: 2026-10-13T13:03:51.337-03:00). */
        internal fun formatarDataHora(epochMs: Long): String =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault())
            )
    }
}
