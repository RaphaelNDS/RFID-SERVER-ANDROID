package com.example.rfid_server.service

import com.example.rfid_server.api.HttpClient
import com.example.rfid_server.view.TagView
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Testa o TagService contra um servidor HTTP falso (MockWebServer), conferindo
 * o que o app envia e como ele interpreta cada resposta do servidor.
 */
class TagServiceTest {

    private lateinit var servidor: MockWebServer
    private lateinit var service: TagService

    private val chave = "chave-de-teste-1234567890"

    @Before
    fun setUp() {
        servidor = MockWebServer()
        servidor.start()

        // Mesmo cliente da produção (com a chave e sem seguir redirecionamentos)
        service = TagService(HttpClient.criar { chave }) { enderecoServidor() }
    }

    @After
    fun tearDown() {
        servidor.shutdown()
    }

    // Igual ao AppConfig.getBaseUrl: "http://ip:porta", sem barra no final
    private fun enderecoServidor() = servidor.url("/").toString().trimEnd('/')

    private fun json(codigo: Int, corpo: String) =
        MockResponse().setResponseCode(codigo).setHeader("Content-Type", "application/json").setBody(corpo)

    private fun proximaRequisicao(): RecordedRequest =
        servidor.takeRequest(5, TimeUnit.SECONDS) ?: throw AssertionError("O app não enviou nenhuma requisição")

    /** Transforma uma chamada com callback em retorno direto, esperando no máximo 5 s. */
    private fun <T> aguardar(chamada: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        var resultado: T? = null

        chamada { resultado = it; latch.countDown() }

        if (!latch.await(5, TimeUnit.SECONDS)) fail("O callback não foi chamado em 5 s")
        @Suppress("UNCHECKED_CAST")
        return resultado as T
    }

    private fun motivo(resposta: RespostaServidor<*>): MotivoFalha =
        (resposta as? RespostaServidor.Falha)?.motivo ?: throw AssertionError("Esperava Falha, veio $resposta")

    // ================= buscarTagAgora =================

    @Test
    fun `buscarTagAgora com tag cadastrada devolve os dados da tag`() {
        servidor.enqueue(
            json(
                200, """
                {"codigoInterno":"CI-01","tipo":"Notebook","marca":"Dell","modelo":"Latitude 5440",
                 "patrimonio":"PAT-100","numeroSerie":"SN-999"}
                """
            )
        )

        val resposta = service.buscarTagAgora("E200ABC")

        assertEquals(
            RespostaServidor.Sucesso(TagView("E200ABC", "CI-01", "Notebook", "Dell", "Latitude 5440", "PAT-100", "SN-999")),
            resposta
        )
    }

    @Test
    fun `buscarTagAgora le a situacao do bem e alerta so para baixado ou extraviado`() {
        servidor.enqueue(json(200, """{"codigoInterno":"CI-01","patrimonio":"PAT-1","situacao":"BAIXADO","situacaoRotulo":"Baixado"}"""))
        servidor.enqueue(json(200, """{"codigoInterno":"CI-02","patrimonio":"PAT-2","situacao":"EMPRESTADO","situacaoRotulo":"Emprestado"}"""))

        val baixado = (service.buscarTagAgora("E1") as RespostaServidor.Sucesso).valor
        val emprestado = (service.buscarTagAgora("E2") as RespostaServidor.Sucesso).valor

        assertEquals("BAIXADO", baixado.situacao)
        assertEquals("Baixado", baixado.alertaSituacao())
        assertEquals("Emprestado: só na ficha, sem alerta no cartão", null, emprestado.alertaSituacao())
        assertEquals("Extraviado sem rótulo usa o código", "EXTRAVIADO", baixado.copy(situacao = "EXTRAVIADO", situacaoRotulo = null).alertaSituacao())
        // Locação (servidor 1.12.0): alugado lido aqui = devolução não registrada; vendido não deveria estar aqui
        assertEquals("Alugado", baixado.copy(situacao = "ALUGADO", situacaoRotulo = "Alugado").alertaSituacao())
        assertEquals("Vendido", baixado.copy(situacao = "VENDIDO", situacaoRotulo = "Vendido").alertaSituacao())
        assertEquals(null, baixado.copy(situacao = "DISPONIVEL", situacaoRotulo = "Disponível").alertaSituacao())
    }

    @Test
    fun `buscarTagAgora chama o endpoint do android com a chave do coletor`() {
        servidor.enqueue(json(404, """{"status":404}"""))

        service.buscarTagAgora("E200ABC")

        val requisicao = proximaRequisicao()
        assertEquals("GET", requisicao.method)
        assertEquals("/api/android/tags/E200ABC", requisicao.path)
        assertEquals(chave, requisicao.getHeader(HttpClient.HEADER_CHAVE))
    }

    @Test
    fun `buscarTagAgora com tipo marca e modelo nulos devolve null nesses campos`() {
        servidor.enqueue(
            json(
                200, """
                {"codigoInterno":"CI-02","tipo":null,"marca":null,"modelo":null,
                 "patrimonio":"PAT-200","numeroSerie":"SN-1"}
                """
            )
        )

        val tag = (service.buscarTagAgora("E200DEF") as RespostaServidor.Sucesso).valor

        assertNull(tag.tipo)
        assertNull(tag.marca)
        assertNull(tag.modelo)
    }

    @Test
    fun `buscarTagAgora com campos obrigatorios nulos ou faltando aceita a tag com eles vazios`() {
        // No Android, getString de um null devolveria o texto "null"; um campo faltando derrubaria a resposta
        servidor.enqueue(json(200, """{"codigoInterno":"CI-03","patrimonio":null}"""))

        val tag = (service.buscarTagAgora("E200GHI") as RespostaServidor.Sucesso).valor

        assertEquals(TagView("E200GHI", "CI-03", null, null, null, "", ""), tag)
    }

    // ================= enviarLoteAgora =================

    private fun resultadosDoLote(corpo: String, quantidade: Int = 3): List<ResultadoLote> {
        servidor.enqueue(json(200, corpo))
        val resposta = service.enviarLoteAgora((1..quantidade).map { "E$it" to 0L }, "sessao-ana")
        return (resposta as RespostaServidor.Sucesso).valor
    }

    @Test
    fun `lote com um item estranho aproveita os outros`() {
        val resultados = resultadosDoLote(
            """{"resultados":[
                {"indice":0,"tag":"E1","status":"CADASTRADA","dados":{"codigoInterno":"CI-1","patrimonio":null,"numeroSerie":"SN"}},
                "não é objeto",
                {"indice":"abc","status":"NAO_CADASTRADA"},
                {"indice":2,"tag":"E3","status":"NAO_CADASTRADA"}
            ]}"""
        )

        assertEquals(listOf(0, 2), resultados.map { it.indice })
        assertEquals(StatusLote.CADASTRADA, resultados[0].status)
        assertEquals("Campo null vira vazio, não o texto \"null\"", "", resultados[0].tag!!.patrimonio)
        assertEquals(StatusLote.NAO_CADASTRADA, resultados[1].status)
    }

    @Test
    fun `lote com dados da tag fora do formato aceita a leitura sem os dados`() {
        val resultado = resultadosDoLote("""{"resultados":[{"indice":0,"tag":"E1","status":"CADASTRADA","dados":"???"}]}""", 1).single()

        assertEquals(StatusLote.CADASTRADA, resultado.status)
        assertNull(resultado.tag)
    }

    @Test
    fun `lote sem indice usa a posicao e status desconhecido vira aceita`() {
        val resultados = resultadosDoLote(
            """{"resultados":[{"status":"NAO_CADASTRADA"},{"status":"DUPLICADA"},{"indice":2,"status":null,"mensagem":null}]}"""
        )

        assertEquals(listOf(0, 1, 2), resultados.map { it.indice })
        assertEquals(listOf(StatusLote.NAO_CADASTRADA, StatusLote.ACEITA, StatusLote.ACEITA), resultados.map { it.status })
        assertNull("mensagem null não vira o texto \"null\"", resultados[2].mensagem)
    }

    @Test
    fun `lote com corpo fora do formato continua erro do servidor`() {
        servidor.enqueue(json(200, """{"outra":"coisa"}"""))

        assertEquals(MotivoFalha.ERRO_SERVIDOR, motivo(service.enviarLoteAgora(listOf("E1" to 0L), "sessao-ana")))
    }

    @Test
    fun `lote espera mais pela resposta e usa o mesmo cliente com a chave`() {
        assertEquals(TagService.LEITURA_LOTE_S * 1000, service.clienteLote.readTimeoutMillis.toLong())
        servidor.enqueue(json(200, """{"resultados":[]}"""))

        service.enviarLoteAgora(listOf("E1" to 0L), "sessao-ana")

        assertEquals(chave, proximaRequisicao().getHeader(HttpClient.HEADER_CHAVE))
    }

    @Test
    fun `buscarTagAgora com 404 indica tag nao cadastrada`() {
        servidor.enqueue(json(404, """{"status":404}"""))

        assertEquals(RespostaServidor.NaoEncontrado, service.buscarTagAgora("E200XYZ"))
    }

    @Test
    fun `buscarTagAgora com 401 e falha de chave e nao tag nao cadastrada`() {
        servidor.enqueue(json(401, """{"status":401,"mensagem":"Chave do dispositivo inválida"}"""))

        assertEquals(MotivoFalha.CHAVE_INVALIDA, motivo(service.buscarTagAgora("E200XYZ")))
    }

    @Test
    fun `buscarTagAgora com erro 500 e erro do servidor`() {
        servidor.enqueue(json(500, """{"status":500}"""))

        assertEquals(
            RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Erro do servidor (HTTP 500)"),
            service.buscarTagAgora("E200XYZ")
        )
    }

    @Test
    fun `buscarTagAgora com erro 400 e recusa definitiva`() {
        servidor.enqueue(json(400, """{"status":400}"""))

        assertEquals(MotivoFalha.REQUISICAO_RECUSADA, motivo(service.buscarTagAgora("E200XYZ")))
    }

    @Test
    fun `413 e 425 sao passageiros e a leitura fica na fila`() {
        // 408 também, mas o próprio OkHttp já repete a requisição uma vez antes de devolver.
        // 429 tem motivo próprio (MUITAS_TENTATIVAS): ver o teste do 429
        listOf(413, 425).forEach { codigo ->
            servidor.enqueue(json(codigo, "{}"))
            assertEquals("HTTP $codigo", MotivoFalha.ERRO_SERVIDOR, motivo(service.buscarTagAgora("E200XYZ")))
        }
    }

    @Test
    fun `buscarTagAgora com HTML no lugar de JSON e erro do servidor`() {
        servidor.enqueue(MockResponse().setResponseCode(200).setBody("<html><body>Login</body></html>"))

        assertEquals(
            RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Resposta inválida do servidor"),
            service.buscarTagAgora("E200XYZ")
        )
    }

    @Test
    fun `buscarTagAgora nao segue redirecionamento para a tela de login`() {
        servidor.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/login"))
        servidor.enqueue(MockResponse().setResponseCode(200).setBody("<html>Login</html>"))

        assertEquals(MotivoFalha.ERRO_SERVIDOR, motivo(service.buscarTagAgora("E200XYZ")))
        assertEquals("Só a requisição original, sem ir até /login", 1, servidor.requestCount)
    }

    @Test
    fun `buscarTagAgora com servidor fora do ar devolve sem conexao`() {
        servidor.shutdown()

        assertEquals(
            RespostaServidor.Falha(MotivoFalha.SERVIDOR_FORA_DO_AR, TagService.MENSAGEM_SERVIDOR_FORA_DO_AR),
            service.buscarTagAgora("E200XYZ")
        )
    }

    @Test
    fun `buscarTagAgora com endereco invalido falha sem fazer requisicao`() {
        val semEndereco = TagService(HttpClient.criar { chave }) { "isto não é uma url" }

        assertEquals(MotivoFalha.ENDERECO_INVALIDO, motivo(semEndereco.buscarTagAgora("E200XYZ")))
        assertEquals(0, servidor.requestCount)
    }

    // ================= buscarHistorico (ficha) =================

    @Test
    fun `ficha le a locacao do servidor 1_12_0 com pedido numerico`() {
        servidor.enqueue(json(200, """{"epc":"E1","situacao":"ALUGADO","situacaoRotulo":"Alugado","pedido":12,
            "cliente":"Construtora X","previsaoDevolucao":"2026-11-30","localEntrega":"Obra Rua Y"}"""))

        val resposta = aguardar<RespostaServidor<HistoricoBem>> { service.buscarHistorico("E1", it) }

        val h = (resposta as RespostaServidor.Sucesso).valor
        assertEquals(listOf("12", "Construtora X", "2026-11-30", "Obra Rua Y"), listOf(h.pedido, h.cliente, h.previsaoDevolucao, h.localEntrega))
        assertEquals(false, h.vazio)
    }

    @Test
    fun `ficha le situacao unidade local e responsavel do servidor 1_6_0`() {
        servidor.enqueue(json(200, """{"epc":"E1","situacao":"BAIXADO","situacaoRotulo":"Baixado","unidade":"Matriz",
            "local":"Sala 101","responsavel":"Ana","ultimoLocal":null}"""))

        val resposta = aguardar<RespostaServidor<HistoricoBem>> { service.buscarHistorico("E1", it) }

        val h = (resposta as RespostaServidor.Sucesso).valor
        assertEquals(listOf("BAIXADO", "Baixado", "Matriz", "Sala 101", "Ana"), listOf(h.situacao, h.situacaoRotulo, h.unidade, h.local, h.responsavel))
        assertEquals(null, h.ultimoLocal)
        assertEquals(false, h.vazio)
    }

    @Test
    fun `ficha de servidor antigo sem os campos novos continua vazia`() {
        servidor.enqueue(json(200, """{"epc":"E1","tipo":"Notebook"}"""))

        val resposta = aguardar<RespostaServidor<HistoricoBem>> { service.buscarHistorico("E1", it) }

        assertEquals(true, (resposta as RespostaServidor.Sucesso).valor.vazio)
    }

    // ================= buscarTagPorPatrimonio =================

    @Test
    fun `buscarTagPorPatrimonio devolve o EPC da tag`() {
        servidor.enqueue(json(200, """{"epc":"E200PAT"}"""))

        val resposta = aguardar<RespostaServidor<String>> { service.buscarTagPorPatrimonio("PAT-100", it) }

        assertEquals(RespostaServidor.Sucesso("E200PAT"), resposta)
        assertEquals("/api/android/tags/patrimonio?valor=PAT-100", proximaRequisicao().path)
    }

    @Test
    fun `buscarTagPorPatrimonio manda o valor com espaco e barra no parametro e nao no caminho`() {
        servidor.enqueue(json(404, """{"status":404}"""))

        aguardar<RespostaServidor<String>> { service.buscarTagPorPatrimonio("PAT 01/A", it) }

        val requisicao = proximaRequisicao()
        assertEquals("/api/android/tags/patrimonio", requisicao.requestUrl!!.encodedPath)
        assertEquals("PAT 01/A", requisicao.requestUrl!!.queryParameter("valor"))
    }

    @Test
    fun `buscarTagPorPatrimonio inexistente devolve nao encontrado`() {
        servidor.enqueue(json(404, """{"status":404}"""))

        val resposta = aguardar<RespostaServidor<String>> { service.buscarTagPorPatrimonio("NAO-EXISTE", it) }

        assertEquals(RespostaServidor.NaoEncontrado, resposta)
    }

    @Test
    fun `buscarTagPorPatrimonio com servidor fora do ar devolve sem conexao`() {
        servidor.shutdown()

        val resposta = aguardar<RespostaServidor<String>> { service.buscarTagPorPatrimonio("PAT-100", it) }

        assertEquals(MotivoFalha.SERVIDOR_FORA_DO_AR, motivo(resposta))
    }

    // ================= testarConexao =================

    private fun statusParaResposta(resposta: MockResponse): StatusConexao {
        servidor.enqueue(resposta)
        return aguardar<ResultadoConexao> { service.testarConexao(it) }.status
    }

    @Test
    fun `testarConexao com 200 indica conectado e usa o ping do android`() {
        val status = statusParaResposta(json(200, """{"status":"ok","dispositivo":"coletor-01"}"""))

        assertEquals(StatusConexao.CONECTADO, status)
        assertEquals("/api/android/ping", proximaRequisicao().path)
    }

    @Test
    fun `testarConexao com 401 ou 403 indica chave invalida`() {
        assertEquals(StatusConexao.CHAVE_INVALIDA, statusParaResposta(json(401, "{}")))
        assertEquals(StatusConexao.CHAVE_INVALIDA, statusParaResposta(json(403, "{}")))
    }

    @Test
    fun `testarConexao com 302 para o login nao e considerado conectado`() {
        val status = statusParaResposta(MockResponse().setResponseCode(302).setHeader("Location", "/login"))

        assertEquals(StatusConexao.RESPOSTA_INESPERADA, status)
    }

    @Test
    fun `testarConexao com 404 indica servidor desatualizado`() {
        assertEquals(StatusConexao.RESPOSTA_INESPERADA, statusParaResposta(json(404, "{}")))
    }

    @Test
    fun `testarConexao com servidor fora do ar indica sem conexao`() {
        servidor.shutdown()

        assertEquals(StatusConexao.SERVIDOR_FORA_DO_AR, aguardar<ResultadoConexao> { service.testarConexao(it) }.status)
    }

    @Test
    fun `coletor sem servidor configurado falha com mensagem propria e sem requisicao`() {
        val naoConfigurado = TagService(HttpClient.criar { chave }) { "" }

        val falha = naoConfigurado.buscarTagAgora("E1") as RespostaServidor.Falha
        assertEquals(MotivoFalha.ENDERECO_INVALIDO, falha.motivo)
        assertEquals(TagService.MENSAGEM_NAO_CONFIGURADO, falha.mensagem)

        val lote = naoConfigurado.enviarLoteAgora(listOf("E1" to 0L), "sessao") as RespostaServidor.Falha
        assertEquals(TagService.MENSAGEM_NAO_CONFIGURADO, lote.mensagem)
        assertEquals(0, servidor.requestCount)
    }

    @Test
    fun `endereco invalido configurado continua com a mensagem de endereco invalido`() {
        val invalido = TagService(HttpClient.criar { chave }) { "isto não é uma url" }

        val falha = invalido.buscarTagAgora("E1") as RespostaServidor.Falha
        assertTrue(falha.mensagem.startsWith("Endereço do servidor inválido"))
    }

    @Test
    fun `testarConexao com endereco invalido indica endereco invalido`() {
        val semEndereco = TagService(HttpClient.criar { chave }) { "" }

        assertEquals(StatusConexao.ENDERECO_INVALIDO, aguardar<ResultadoConexao> { semEndereco.testarConexao(it) }.status)
    }

    // ================= envios =================

    @Test
    fun `enviarLeituraAgora faz POST com a tag e a hora real da leitura`() {
        servidor.enqueue(MockResponse().setResponseCode(200))
        val lidoEm = 1_760_371_431_337L

        val resposta = service.enviarLeituraAgora("E200LIDA", lidoEm)

        assertEquals(RespostaServidor.Sucesso(Unit), resposta)

        val requisicao = proximaRequisicao()
        assertEquals("POST", requisicao.method)
        assertEquals("/api/android/leitura", requisicao.path)
        assertEquals(chave, requisicao.getHeader(HttpClient.HEADER_CHAVE))
        assertTrue(requisicao.getHeader("Content-Type").orEmpty().startsWith("application/json"))

        val corpo = JSONObject(requisicao.body.readUtf8())
        assertEquals("E200LIDA", corpo.getString("tag"))
        // ISO-8601 com fuso, que o servidor (OffsetDateTime) entende, apontando para o mesmo instante
        assertEquals(lidoEm, OffsetDateTime.parse(corpo.getString("lidoEm")).toInstant().toEpochMilli())
    }

    @Test
    fun `enviarNaoCadastradaAgora faz POST com a tag em JSON`() {
        servidor.enqueue(MockResponse().setResponseCode(200))

        assertEquals(RespostaServidor.Sucesso(Unit), service.enviarNaoCadastradaAgora("E200NOVA"))

        val requisicao = proximaRequisicao()
        assertEquals("POST", requisicao.method)
        assertEquals("/api/android/naocadastrada", requisicao.path)
        assertEquals("E200NOVA", JSONObject(requisicao.body.readUtf8()).getString("tag"))
    }

    @Test
    fun `envio com 404 indica servidor desatualizado e nao tag nao encontrada`() {
        servidor.enqueue(json(404, "{}"))

        assertEquals(
            RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Servidor desatualizado (HTTP 404)"),
            service.enviarLeituraAgora("E200", 0L)
        )
    }

    @Test
    fun `envio classifica as falhas pelo motivo`() {
        servidor.enqueue(json(401, "{}"))
        servidor.enqueue(json(400, "{}"))
        servidor.enqueue(json(503, "{}"))

        assertEquals(MotivoFalha.CHAVE_INVALIDA, motivo(service.enviarNaoCadastradaAgora("E1")))
        assertEquals(MotivoFalha.REQUISICAO_RECUSADA, motivo(service.enviarNaoCadastradaAgora("E2")))
        assertEquals(MotivoFalha.ERRO_SERVIDOR, motivo(service.enviarNaoCadastradaAgora("E3")))
    }

    @Test
    fun `envio com servidor fora do ar devolve sem conexao`() {
        servidor.shutdown()

        assertEquals(MotivoFalha.SERVIDOR_FORA_DO_AR, motivo(service.enviarLeituraAgora("E200", 0L)))
    }

    // ================= inventário =================

    @Test
    fun `listarLocais devolve os nomes do servidor`() {
        servidor.enqueue(json(200, """["Almoxarifado","Sala 101"]"""))

        val resposta = aguardar<RespostaServidor<List<String>>> { service.listarLocais(it) }

        assertEquals(RespostaServidor.Sucesso(listOf("Almoxarifado", "Sala 101")), resposta)
        assertEquals("/api/android/locais", proximaRequisicao().path)
    }

    @Test
    fun `listarLocais com resposta que nao e lista e erro do servidor`() {
        servidor.enqueue(json(200, """{"status":"ok"}"""))

        val resposta = aguardar<RespostaServidor<List<String>>> { service.listarLocais(it) }

        assertEquals(MotivoFalha.ERRO_SERVIDOR, motivo(resposta))
    }

    @Test
    fun `itens de inventario desconhecido no servidor voltam como nao encontrado`() {
        servidor.enqueue(json(404, "{}"))
        servidor.enqueue(json(404, "{}"))

        assertEquals(RespostaServidor.NaoEncontrado, service.enviarItensInventarioAgora("inv-1", listOf("E1" to 0L)))
        assertEquals(RespostaServidor.NaoEncontrado, service.finalizarInventarioAgora("inv-1", 0L))
    }

    @Test
    fun `abertura de inventario com 404 indica servidor sem a API`() {
        servidor.enqueue(json(404, "{}"))

        assertEquals(MotivoFalha.ERRO_SERVIDOR, motivo(service.abrirInventarioAgora("inv-1", "Sala", 0L)))
    }

    @Test
    fun `id do inventario com caracteres especiais vai codificado na URL`() {
        servidor.enqueue(json(200, "{}"))

        service.finalizarInventarioAgora("a b/c", 0L)

        assertEquals("/api/android/inventarios/a%20b%2Fc/finalizar", proximaRequisicao().path)
    }

    @Test
    fun `buscarEsperado le a lista do local com o local codificado na url`() {
        servidor.enqueue(json(200, """
            {"local":"Sala 1/A","temReferencia":true,"inventarioReferencia":"inv-1",
             "finalizadoEm":"2026-10-10T10:30:00-03:00","operador":"Ana",
             "itens":[{"tag":"E1","patrimonio":"PAT-1","codigoInterno":null,"tipo":"Notebook","marca":"Dell","modelo":null}]}
        """))

        val resposta = aguardar<RespostaServidor<EsperadoBaixado>> { service.buscarEsperado("Sala 1/A", callback = it) }

        val esperado = (resposta as RespostaServidor.Sucesso).valor.esperado!!
        assertTrue(esperado.temReferencia)
        assertEquals("Ana", esperado.operador)
        assertEquals("PAT-1", esperado.itens.single().patrimonio)
        assertNull(esperado.itens.single().modelo)
        assertEquals("/api/android/inventarios/esperado?local=Sala%201%2FA", proximaRequisicao().path)
    }

    @Test
    fun `buscarEsperado em servidor antigo volta NaoEncontrado`() {
        servidor.enqueue(json(404, "{}"))

        val resposta = aguardar<RespostaServidor<EsperadoBaixado>> { service.buscarEsperado("Sala 1", callback = it) }

        assertEquals(RespostaServidor.NaoEncontrado, resposta)
    }

    @Test
    fun `buscarEsperado manda o ETag guardado e o 304 volta sem lista`() {
        servidor.enqueue(MockResponse().setResponseCode(304).setHeader("ETag", "\"abc123\""))

        val resposta = aguardar<RespostaServidor<EsperadoBaixado>> { service.buscarEsperado("Sala 1", "\"abc123\"", it) }

        assertEquals(RespostaServidor.Sucesso(EsperadoBaixado(esperado = null, etag = "\"abc123\"")), resposta)
        assertEquals("\"abc123\"", proximaRequisicao().getHeader("If-None-Match"))
    }

    @Test
    fun `buscarEsperado devolve o ETag da lista nova e sem ETag guardado nao manda If-None-Match`() {
        servidor.enqueue(json(200, """{"local":"Sala 1","temReferencia":false,"itens":[]}""").setHeader("ETag", "\"novo\""))

        val resposta = aguardar<RespostaServidor<EsperadoBaixado>> { service.buscarEsperado("Sala 1", callback = it) }

        val baixado = (resposta as RespostaServidor.Sucesso).valor
        assertEquals("\"novo\"", baixado.etag)
        assertFalse(baixado.esperado!!.temReferencia)
        assertNull(proximaRequisicao().getHeader("If-None-Match"))
    }

    @Test
    fun `servidor sem ETag devolve a lista com etag nulo`() {
        servidor.enqueue(json(200, """{"local":"Sala 1","temReferencia":false,"itens":[]}"""))

        val resposta = aguardar<RespostaServidor<EsperadoBaixado>> { service.buscarEsperado("Sala 1", "\"velho\"", it) }

        assertNull((resposta as RespostaServidor.Sucesso).valor.etag)
    }

    // ================= erros do app =================

    @Test
    fun `enviarErrosAgora manda o lote sem sessao de operador`() {
        servidor.enqueue(json(202, """{"recebidos":1,"novos":1,"descartados":0}"""))
        val itens = org.json.JSONArray().put(JSONObject().put("id", "x").put("tipo", "FALHA_GERAL").put("mensagem", "m"))

        val resposta = service.enviarErrosAgora(itens)

        assertTrue(resposta is RespostaServidor.Sucesso)
        val requisicao = proximaRequisicao()
        assertEquals("/api/android/erros", requisicao.path)
        assertNull(requisicao.getHeader(HttpClient.HEADER_SESSAO))
        assertEquals(1, JSONObject(requisicao.body.readUtf8()).getJSONArray("itens").length())
    }

    @Test
    fun `enviarErrosAgora em servidor antigo volta NaoEncontrado`() {
        servidor.enqueue(json(404, "{}"))

        assertEquals(RespostaServidor.NaoEncontrado, service.enviarErrosAgora(org.json.JSONArray()))
    }

    // ================= atualização do app =================

    private val apkFalso = "conteúdo do APK".toByteArray()
    private val shaApkFalso = java.security.MessageDigest.getInstance("SHA-256").digest(apkFalso).joinToString("") { "%02x".format(it) }
    private fun versao(sha: String = shaApkFalso, tamanho: Long = apkFalso.size.toLong()) =
        com.example.rfid_server.dados.VersaoPublicada("1.5.0", 20, tamanho, sha, null)

    @Test
    fun `consultarAtualizacao sem versao publicada volta NaoEncontrado e vai sem sessao`() {
        servidor.enqueue(MockResponse().setResponseCode(204))

        val resposta = aguardar<RespostaServidor<com.example.rfid_server.dados.VersaoPublicada>> { service.consultarAtualizacao(it) }

        assertEquals(RespostaServidor.NaoEncontrado, resposta)
        val requisicao = proximaRequisicao()
        assertEquals("/api/android/atualizacao", requisicao.path)
        assertNull(requisicao.getHeader(HttpClient.HEADER_SESSAO))
    }

    @Test
    fun `consultarAtualizacao le a versao publicada`() {
        servidor.enqueue(json(200, """{"versaoNome":"1.5.0","codigo":20,"tamanho":10,"sha256":"$shaApkFalso","notas":null}"""))

        val resposta = aguardar<RespostaServidor<com.example.rfid_server.dados.VersaoPublicada>> { service.consultarAtualizacao(it) }

        assertEquals(20, (resposta as RespostaServidor.Sucesso).valor.codigo)
    }

    @Test
    fun `baixarAtualizacao confere o SHA-256 e devolve o arquivo`() {
        servidor.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(apkFalso)))
        val destino = java.io.File.createTempFile("atualizacao", ".apk").apply { delete() }
        val progresso = mutableListOf<Long>()

        val resposta = service.baixarAtualizacaoAgora(versao(), destino) { progresso += it }

        assertTrue(resposta is RespostaServidor.Sucesso)
        assertTrue(destino.readBytes().contentEquals(apkFalso))
        assertEquals(apkFalso.size.toLong(), progresso.last())
        assertEquals("/api/android/atualizacao/apk", proximaRequisicao().path)
        destino.delete()
    }

    @Test
    fun `arquivo baixado que nao confere e apagado`() {
        servidor.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(apkFalso)))
        val destino = java.io.File.createTempFile("atualizacao", ".apk").apply { delete() }

        val resposta = service.baixarAtualizacaoAgora(versao(sha = "0".repeat(64)), destino)

        assertTrue((resposta as RespostaServidor.Falha).mensagem.contains("não confere"))
        assertTrue("Arquivo errado não fica no aparelho", !destino.exists())
    }

    @Test
    fun `download maior que o publicado e interrompido e apagado`() {
        // 1 MB no lugar de um APK publicado com 16 bytes: para no primeiro bloco que passa do tamanho
        servidor.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(ByteArray(1024 * 1024))))
        val destino = java.io.File.createTempFile("atualizacao", ".apk").apply { delete() }
        val progresso = mutableListOf<Long>()

        val resposta = service.baixarAtualizacaoAgora(versao(tamanho = 16), destino) { progresso += it }

        assertTrue((resposta as RespostaServidor.Falha).mensagem.contains("maior que o esperado"))
        assertTrue("Arquivo errado não fica no aparelho", !destino.exists())
        assertTrue("Nada além do tamanho publicado foi gravado", progresso.all { it <= 16 })
    }

    @Test
    fun `download de outra versao publicada no meio e recusado sem gravar nada`() {
        servidor.enqueue(MockResponse().setResponseCode(200).setHeader("X-Versao-Codigo", "21").setBody(okio.Buffer().write(apkFalso)))
        val destino = java.io.File.createTempFile("atualizacao", ".apk").apply { delete() }

        val resposta = service.baixarAtualizacaoAgora(versao(), destino)

        assertEquals(RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, TagService.MENSAGEM_VERSAO_TROCADA), resposta)
        assertTrue("Nada gravado", !destino.exists())
    }

    @Test
    fun `download com o codigo da versao consultada segue normal`() {
        servidor.enqueue(MockResponse().setResponseCode(200).setHeader("X-Versao-Codigo", "20").setBody(okio.Buffer().write(apkFalso)))
        val destino = java.io.File.createTempFile("atualizacao", ".apk").apply { delete() }

        val resposta = service.baixarAtualizacaoAgora(versao(), destino)

        assertTrue(resposta is RespostaServidor.Sucesso)
        destino.delete()
    }

    // ================= cadastro de etiqueta =================

    private fun cadastroCompleto() = com.example.rfid_server.dados.CadastroEntity(
        epc = "E-NOVA", codigoInterno = "TI-1", patrimonio = "PAT-1", numeroSerie = "SN-1",
        tipoId = 1, marcaId = 2, modeloId = 3, criadoEm = 0
    )

    @Test
    fun `cadastrarTagAgora envia os dados no formato do servidor`() {
        servidor.enqueue(json(200, """{"resultado":"CADASTRADA"}"""))

        assertEquals(RespostaServidor.Sucesso(Unit), service.cadastrarTagAgora(cadastroCompleto()))

        val requisicao = proximaRequisicao()
        assertEquals("/api/android/tags", requisicao.path)
        val corpo = JSONObject(requisicao.body.readUtf8())
        assertEquals("E-NOVA", corpo.getString("tag"))
        assertEquals("TI-1", corpo.getString("codigoInterno"))
        assertEquals(1, corpo.getLong("tipoEquipamentoId"))
        assertEquals(3, corpo.getLong("modeloId"))
    }

    @Test
    fun `cadastro sem tipo marca e modelo manda null`() {
        servidor.enqueue(json(200, "{}"))

        service.cadastrarTagAgora(cadastroCompleto().copy(tipoId = null, marcaId = null, modeloId = null))

        val corpo = JSONObject(proximaRequisicao().body.readUtf8())
        assertTrue(corpo.isNull("tipoEquipamentoId"))
        assertTrue(corpo.isNull("modeloId"))
    }

    @Test
    fun `recusas do cadastro trazem a mensagem do servidor e nao travam como chave invalida`() {
        servidor.enqueue(json(403, """{"status":403,"mensagem":"O coletor c2 não tem permissão para cadastrar etiquetas"}"""))
        servidor.enqueue(json(409, """{"status":409,"mensagem":"Esta etiqueta já está cadastrada (patrimônio P9)"}"""))
        servidor.enqueue(json(400, "não é json"))

        assertEquals(
            RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, "O coletor c2 não tem permissão para cadastrar etiquetas"),
            service.cadastrarTagAgora(cadastroCompleto())
        )
        assertEquals(
            RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, "Esta etiqueta já está cadastrada (patrimônio P9)"),
            service.cadastrarTagAgora(cadastroCompleto())
        )
        assertEquals(
            RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, "Servidor recusou o cadastro (HTTP 400)"),
            service.cadastrarTagAgora(cadastroCompleto())
        )
    }

    @Test
    fun `cadastro com chave invalida ou servidor fora do ar interrompe`() {
        servidor.enqueue(json(401, "{}"))
        assertEquals(MotivoFalha.CHAVE_INVALIDA, motivo(service.cadastrarTagAgora(cadastroCompleto())))

        servidor.shutdown()
        assertEquals(MotivoFalha.SERVIDOR_FORA_DO_AR, motivo(service.cadastrarTagAgora(cadastroCompleto())))
    }

    @Test
    fun `buscarCatalogo devolve o JSON conferido para guardar`() {
        val corpo = """{"podeCadastrar":true,"tipos":[]}"""
        servidor.enqueue(json(200, corpo))

        val resposta = aguardar<RespostaServidor<String>> { service.buscarCatalogo(it) }

        assertEquals(RespostaServidor.Sucesso(corpo), resposta)
        assertEquals("/api/android/catalogo", proximaRequisicao().path)
    }

    @Test
    fun `buscarCatalogo com resposta fora do formato e erro do servidor`() {
        servidor.enqueue(json(200, """{"status":"ok"}"""))

        val resposta = aguardar<RespostaServidor<String>> { service.buscarCatalogo(it) }

        assertEquals(MotivoFalha.ERRO_SERVIDOR, motivo(resposta))
    }

    // ================= login e autoria do operador =================

    @Test
    fun `entrar devolve a sessao do operador`() {
        servidor.enqueue(json(200, """
            {"token":"tok-1","expiraEm":"2026-10-14T02:00:00-03:00","nome":"Ana","email":"ana@x.com",
             "podeLer":true,"podeCadastrar":true,"senhaFraca":false}
        """))

        val resposta = aguardar<RespostaServidor<com.example.rfid_server.SessaoOperador>> { service.entrar("ana@x.com", "Kx9mPq2v", it) }

        val sessao = (resposta as RespostaServidor.Sucesso).valor
        assertEquals("tok-1", sessao.token)
        assertEquals(true, sessao.podeCadastrar)

        val requisicao = proximaRequisicao()
        assertEquals("/api/android/login", requisicao.path)
        assertEquals("ana@x.com", JSONObject(requisicao.body.readUtf8()).getString("email"))
        assertNull("O login não leva sessão de ninguém", requisicao.getHeader(HttpClient.HEADER_SESSAO))
    }

    @Test
    fun `login recusado mostra a mensagem do servidor`() {
        servidor.enqueue(json(429, """{"status":429,"mensagem":"Muitas tentativas sem sucesso. Aguarde 15 minuto(s) e tente de novo."}"""))

        val resposta = aguardar<RespostaServidor<com.example.rfid_server.SessaoOperador>> { service.entrar("ana@x.com", "errada", it) }

        assertEquals("Muitas tentativas sem sucesso. Aguarde 15 minuto(s) e tente de novo.", (resposta as RespostaServidor.Falha).mensagem)
    }

    @Test
    fun `login sem rede avisa que precisa de conexao`() {
        servidor.shutdown()

        val resposta = aguardar<RespostaServidor<com.example.rfid_server.SessaoOperador>> { service.entrar("ana@x.com", "x", it) }

        assertEquals(MotivoFalha.SERVIDOR_FORA_DO_AR, motivo(resposta))
    }

    @Test
    fun `envio da fila leva a sessao de quem leu e o momento da leitura`() {
        servidor.enqueue(json(200, "{}"))
        val lidoEm = 1_760_000_000_000L

        service.enviarLeituraAgora("E1", lidoEm, sessao = "sessao-ana")

        val requisicao = proximaRequisicao()
        assertEquals("sessao-ana", requisicao.getHeader(HttpClient.HEADER_SESSAO))
        assertEquals(lidoEm, OffsetDateTime.parse(requisicao.getHeader(HttpClient.HEADER_MOMENTO)).toInstant().toEpochMilli())
    }

    @Test
    fun `sessao invalida do operador e diferente de chave invalida`() {
        servidor.enqueue(json(401, """{"status":401,"codigo":"SESSAO_OPERADOR","mensagem":"Sessão do operador expirada: faça login de novo"}"""))
        servidor.enqueue(json(401, """{"status":401,"mensagem":"Chave do dispositivo inválida"}"""))

        assertEquals(
            RespostaServidor.Falha(MotivoFalha.SESSAO_INVALIDA, "Sessão do operador expirada: faça login de novo"),
            service.enviarLeituraAgora("E1", 0L, "sessao-velha")
        )
        assertEquals(MotivoFalha.CHAVE_INVALIDA, motivo(service.enviarLeituraAgora("E1", 0L, "sessao")))
    }

    @Test
    fun `mensagens longas do 401 da sessao do 403 e do item recusado do lote sao cortadas em 300 caracteres`() {
        val longa = "b".repeat(1000)
        servidor.enqueue(json(401, """{"status":401,"codigo":"SESSAO_OPERADOR","mensagem":"$longa","motivo":"EXPIRADA"}"""))
        servidor.enqueue(json(403, """{"status":403,"mensagem":"$longa"}"""))

        val sessao = service.enviarLeituraAgora("E1", 0L, "sessao") as RespostaServidor.Falha
        assertEquals(MotivoFalha.SESSAO_INVALIDA, sessao.motivo)
        assertEquals("EXPIRADA", sessao.motivoSessao)
        assertEquals(TagService.MAX_MENSAGEM_SERVIDOR, sessao.mensagem.length)
        assertTrue(sessao.mensagem.endsWith("…"))

        val permissao = service.buscarTagAgora("E1", Autoria("sessao", 0L)) as RespostaServidor.Falha
        assertEquals(MotivoFalha.REQUISICAO_RECUSADA, permissao.motivo)
        assertEquals(TagService.MAX_MENSAGEM_SERVIDOR, permissao.mensagem.length)

        val item = resultadosDoLote("""{"resultados":[{"indice":0,"status":"RECUSADA","mensagem":"$longa"}]}""", 1).single()
        assertEquals(StatusLote.RECUSADA, item.status)
        assertEquals(TagService.MAX_MENSAGEM_SERVIDOR, item.mensagem!!.length)
        assertTrue(item.mensagem!!.endsWith("…"))
    }

    @Test
    fun `operador sem permissao e recusa da acao com a mensagem do servidor`() {
        servidor.enqueue(json(403, """{"status":403,"mensagem":"Seu usuário não tem permissão para esta ação"}"""))

        assertEquals(
            RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, "Seu usuário não tem permissão para esta ação"),
            service.buscarTagAgora("E1", Autoria("sessao", 0L))
        )
    }

    @Test
    fun `sair avisa o servidor com a sessao que esta saindo`() {
        servidor.enqueue(json(200, """{"status":"ok"}"""))

        service.sair("tok-saindo")

        val requisicao = proximaRequisicao()
        assertEquals("/api/android/logout", requisicao.path)
        assertEquals("tok-saindo", requisicao.getHeader(HttpClient.HEADER_SESSAO))
    }

    // ================= liberarPin =================

    @Test
    fun `liberarPin faz POST em pin-liberar com email e senha do administrador`() {
        servidor.enqueue(json(200, """{"status":"ok"}"""))

        val resposta = aguardar<RespostaServidor<Unit>> { service.liberarPin("admin@x.com", "segredo", it) }

        assertTrue(resposta is RespostaServidor.Sucesso)
        val requisicao = proximaRequisicao()
        assertEquals("POST", requisicao.method)
        // Barra de verdade, não pin%2Fliberar (o Tomcat recusa barra codificada com 400)
        assertEquals("/api/android/pin/liberar", requisicao.path)
        val corpo = JSONObject(requisicao.body.readUtf8())
        assertEquals("admin@x.com", corpo.getString("email"))
        assertEquals("segredo", corpo.getString("senha"))
    }

    @Test
    fun `liberarPin com senha errada devolve a mensagem do servidor`() {
        servidor.enqueue(json(401, """{"status":401,"mensagem":"Email ou senha incorretos"}"""))

        assertEquals(
            RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, "Email ou senha incorretos"),
            aguardar<RespostaServidor<Unit>> { service.liberarPin("admin@x.com", "errada", it) }
        )
    }

    @Test
    fun `liberarPin com 404 avisa que o servidor esta desatualizado`() {
        servidor.enqueue(json(404, """{"status":404}"""))

        assertEquals(
            RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Servidor desatualizado: atualize o servidor para liberar o PIN"),
            aguardar<RespostaServidor<Unit>> { service.liberarPin("admin@x.com", "segredo", it) }
        )
    }

    // ================= mensagens do servidor nos 4xx =================

    @Test
    fun `409 do Localizar traz a mensagem do servidor e continua recusa definitiva`() {
        val texto = "O patrimônio P1 está em 2 etiquetas. Corrija o cadastro na web para localizar."
        servidor.enqueue(json(409, """{"status":409,"mensagem":"$texto"}"""))

        val resposta = aguardar<RespostaServidor<String>> { service.buscarTagPorPatrimonio("P1", it) }

        assertEquals(RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, texto), resposta)
    }

    @Test
    fun `400 na abertura do inventario traz a mensagem do servidor`() {
        servidor.enqueue(json(400, """{"status":400,"mensagem":"O local pode ter no máximo 120 caracteres"}"""))

        assertEquals(
            RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, "O local pode ter no máximo 120 caracteres"),
            service.abrirInventarioAgora("inv-1", "x".repeat(130), 0L)
        )
    }

    @Test
    fun `4xx sem mensagem ou com corpo que nao e JSON usa o texto padrao`() {
        servidor.enqueue(json(400, """{"status":400}"""))
        servidor.enqueue(json(409, "não é json"))
        servidor.enqueue(json(400, """{"status":400,"mensagem":"   "}"""))

        assertEquals(
            RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, "Servidor recusou a requisição (HTTP 400)"),
            service.buscarTagAgora("E1")
        )
        assertEquals(
            RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, "Servidor recusou a requisição (HTTP 409)"),
            service.buscarTagAgora("E2")
        )
        assertEquals(
            RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, "Servidor recusou a requisição (HTTP 400)"),
            service.buscarTagAgora("E3")
        )
    }

    @Test
    fun `mensagem longa do servidor e cortada em 300 caracteres`() {
        servidor.enqueue(json(400, """{"status":400,"mensagem":"${"a".repeat(1000)}"}"""))

        val mensagem = (service.buscarTagAgora("E1") as RespostaServidor.Falha).mensagem

        assertEquals(TagService.MAX_MENSAGEM_SERVIDOR, mensagem.length)
        assertTrue(mensagem.endsWith("…"))
    }

    @Test
    fun `429 traz a mensagem do servidor e tem motivo proprio de muitas tentativas`() {
        val texto = "Muitas tentativas com chave inválida. Aguarde alguns minutos."
        servidor.enqueue(json(429, """{"status":429,"mensagem":"$texto"}"""))
        servidor.enqueue(json(429, "{}"))

        assertEquals(RespostaServidor.Falha(MotivoFalha.MUITAS_TENTATIVAS, texto), service.enviarNaoCadastradaAgora("E1"))
        val semMensagem = service.enviarNaoCadastradaAgora("E2") as RespostaServidor.Falha
        assertEquals(MotivoFalha.MUITAS_TENTATIVAS, semMensagem.motivo)
        assertTrue(semMensagem.mensagem, semMensagem.mensagem.contains("aguarde alguns minutos"))
    }

    @Test
    fun `503 nao usa a mensagem do corpo`() {
        servidor.enqueue(json(503, """{"status":503,"mensagem":"qualquer coisa"}"""))

        assertEquals(RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "Erro do servidor (HTTP 503)"), service.buscarTagAgora("E1"))
    }

    @Test
    fun `testarConexao com 429 indica muitas tentativas e nao servidor desatualizado`() {
        val status = statusParaResposta(json(429, """{"status":429,"mensagem":"Muitas tentativas com chave inválida. Aguarde alguns minutos."}"""))

        assertEquals(StatusConexao.MUITAS_TENTATIVAS, status)
    }

    // ================= transferência de local (servidor 1.7.0) =================

    @Test
    fun `transferirAgora manda local e observacao com a sessao e le a guia`() {
        servidor.enqueue(json(200, """{"local":"SALA 12","situacao":"EM_USO","situacaoRotulo":"Em uso","transferencia":57,"jaEstava":false}"""))

        val resposta = service.transferirAgora("E2801160", "SALA 12", "mudou de sala", "tok-ana")

        val r = (resposta as RespostaServidor.Sucesso).valor
        assertEquals(57L, r.guia)
        assertEquals(false, r.jaEstava)
        assertEquals("SALA 12", r.local)
        val requisicao = proximaRequisicao()
        assertEquals("POST", requisicao.method)
        assertEquals("/api/android/tags/E2801160/transferencia", requisicao.path)
        assertEquals("tok-ana", requisicao.getHeader(HttpClient.HEADER_SESSAO))
        val corpo = JSONObject(requisicao.body.readUtf8())
        assertEquals("SALA 12", corpo.getString("local"))
        assertEquals("mudou de sala", corpo.getString("observacao"))
    }

    @Test
    fun `transferirAgora de novo para o mesmo local vem sem guia e sem observacao no corpo`() {
        servidor.enqueue(json(200, """{"local":"SALA 12","situacao":"EM_USO","situacaoRotulo":"Em uso","transferencia":null,"jaEstava":true}"""))

        val r = (service.transferirAgora("E1", "SALA 12", null, "tok") as RespostaServidor.Sucesso).valor

        assertNull(r.guia)
        assertEquals(true, r.jaEstava)
        assertEquals(false, JSONObject(proximaRequisicao().body.readUtf8()).has("observacao"))
    }

    @Test
    fun `transferirAgora recusada traz o motivo do servidor`() {
        servidor.enqueue(json(404, """{"status":404,"mensagem":"Etiqueta não cadastrada"}"""))
        servidor.enqueue(json(409, """{"status":409,"mensagem":"Bem baixado não pode ser transferido"}"""))
        servidor.enqueue(json(400, """{"status":400,"mensagem":"Local SALA X desconhecido ou desativado"}"""))
        servidor.enqueue(json(403, """{"status":403,"mensagem":"Sem permissão para transferir"}"""))

        listOf("Etiqueta não cadastrada", "Bem baixado não pode ser transferido", "Local SALA X desconhecido ou desativado",
            "Sem permissão para transferir").forEach { esperado ->
            val falha = service.transferirAgora("E1", "SALA X", null, "tok") as RespostaServidor.Falha
            assertEquals(MotivoFalha.REQUISICAO_RECUSADA, falha.motivo)
            assertEquals(esperado, falha.mensagem)
        }
    }
}
