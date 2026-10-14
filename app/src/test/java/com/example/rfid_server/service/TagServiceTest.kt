package com.example.rfid_server.service

import com.example.rfid_server.api.HttpClient
import com.example.rfid_server.view.TagView
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
            RespostaServidor.Falha(MotivoFalha.SEM_CONEXAO, "Sem conexão com o servidor"),
            service.buscarTagAgora("E200XYZ")
        )
    }

    @Test
    fun `buscarTagAgora com endereco invalido falha sem fazer requisicao`() {
        val semEndereco = TagService(HttpClient.criar { chave }) { "isto não é uma url" }

        assertEquals(MotivoFalha.ENDERECO_INVALIDO, motivo(semEndereco.buscarTagAgora("E200XYZ")))
        assertEquals(0, servidor.requestCount)
    }

    // ================= buscarTagPorPatrimonio =================

    @Test
    fun `buscarTagPorPatrimonio devolve o EPC da tag`() {
        servidor.enqueue(json(200, """{"epc":"E200PAT"}"""))

        val resposta = aguardar<RespostaServidor<String>> { service.buscarTagPorPatrimonio("PAT-100", it) }

        assertEquals(RespostaServidor.Sucesso("E200PAT"), resposta)
        assertEquals("/api/android/tags/patrimonio/PAT-100", proximaRequisicao().path)
    }

    @Test
    fun `buscarTagPorPatrimonio codifica espaco e barra na URL`() {
        servidor.enqueue(json(404, """{"status":404}"""))

        aguardar<RespostaServidor<String>> { service.buscarTagPorPatrimonio("PAT 01/A", it) }

        assertEquals("/api/android/tags/patrimonio/PAT%2001%2FA", proximaRequisicao().path)
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

        assertEquals(MotivoFalha.SEM_CONEXAO, motivo(resposta))
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

        assertEquals(StatusConexao.SEM_CONEXAO, aguardar<ResultadoConexao> { service.testarConexao(it) }.status)
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

        assertEquals(MotivoFalha.SEM_CONEXAO, motivo(service.enviarLeituraAgora("E200", 0L)))
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

        val resposta = aguardar<RespostaServidor<com.example.rfid_server.dados.EsperadoLocal>> { service.buscarEsperado("Sala 1/A", it) }

        val esperado = (resposta as RespostaServidor.Sucesso).valor
        assertTrue(esperado.temReferencia)
        assertEquals("Ana", esperado.operador)
        assertEquals("PAT-1", esperado.itens.single().patrimonio)
        assertNull(esperado.itens.single().modelo)
        assertEquals("/api/android/inventarios/esperado?local=Sala%201%2FA", proximaRequisicao().path)
    }

    @Test
    fun `buscarEsperado em servidor antigo volta NaoEncontrado`() {
        servidor.enqueue(json(404, "{}"))

        val resposta = aguardar<RespostaServidor<com.example.rfid_server.dados.EsperadoLocal>> { service.buscarEsperado("Sala 1", it) }

        assertEquals(RespostaServidor.NaoEncontrado, resposta)
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
        assertEquals(MotivoFalha.SEM_CONEXAO, motivo(service.cadastrarTagAgora(cadastroCompleto())))
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

        assertEquals(MotivoFalha.SEM_CONEXAO, motivo(resposta))
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
}
