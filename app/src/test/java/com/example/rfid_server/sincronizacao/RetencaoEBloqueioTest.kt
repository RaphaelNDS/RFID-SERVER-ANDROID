package com.example.rfid_server.sincronizacao

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.rfid_server.telas.RegrasFaixaSincronizacao
import com.example.rfid_server.api.HttpClient
import com.example.rfid_server.dados.CadastroEntity
import com.example.rfid_server.dados.FakeCadastroDao
import com.example.rfid_server.dados.FakeLeituraDao
import com.example.rfid_server.dados.LeituraEntity
import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.TagService
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Estado da passada com registros retidos por sessão recusada (sessão atual x sessão anterior) e com o coletor
 * bloqueado pelo servidor por muitas tentativas (HTTP 429).
 */
class RetencaoEBloqueioTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private lateinit var servidor: MockWebServer
    private val leituras = FakeLeituraDao()
    private val cadastros = FakeCadastroDao()
    private var agora = System.currentTimeMillis()
    private var novasTentativas = 0
    private var enviosDeErros = 0
    private val erros = mutableListOf<Pair<String, String>>()

    // Sessão atual do aparelho: o Bruno entrou depois da sessão da Ana
    private var tokenAtual: String? = "token-bruno"
    private val fimDaSessao = FimDaSessaoAtual(
        tokenAtual = { tokenAtual },
        referenciaDe = { "sessao1:ref-$it" },
        encerrar = { _, _ -> false }
    )

    @Before
    fun setUp() {
        servidor = MockWebServer()
        servidor.start()
    }

    @After
    fun tearDown() {
        servidor.shutdown()
    }

    private fun json(codigo: Int, corpo: String = "{}") =
        MockResponse().setResponseCode(codigo).setHeader("Content-Type", "application/json").setBody(corpo)

    private val sessaoRecusada = json(
        401, JSONObject().put("status", 401).put("codigo", "SESSAO_OPERADOR").put("mensagem", "Sessão desconhecida").toString()
    )

    private fun tagService() = TagService(HttpClient.criar { "chave-teste" }) { servidor.url("/").toString().trimEnd('/') }

    private fun sincronizador(comCadastros: Boolean = false): Sincronizador {
        val tagService = tagService()
        return Sincronizador(
            dao = leituras,
            tagService = tagService,
            executor = Executor { it.run() },
            agendarNovaTentativa = { novasTentativas++ },
            cadastros = if (comCadastros) SincronizadorCadastros(
                cadastros, leituras, tagService, relogio = { agora }, ehSessaoAtual = fimDaSessao::ehAtual
            ) else null,
            registrarErro = { tipo, mensagem -> erros += tipo to mensagem },
            enviarErros = { enviosDeErros++ },
            relogio = { agora },
            ehSessaoAtual = fimDaSessao::ehAtual
        )
    }

    private fun cadastro(epc: String, sessao: String) = CadastroEntity(
        epc = epc, codigoInterno = "TI-1", patrimonio = "PAT-1", numeroSerie = "SN-1",
        tipoId = null, tipoNome = null, marcaId = null, marcaNome = null, modeloId = null, modeloNome = null,
        criadoEm = 0, sessaoOperador = sessao
    )

    // ---------- Sessão atual x sessão anterior ----------

    @Test
    fun `FimDaSessaoAtual reconhece a sessao atual pela referencia ou pelo token puro`() {
        assertTrue(fimDaSessao.ehAtual("sessao1:ref-token-bruno"))
        assertTrue("Registro de antes da 1.6.1 (token puro)", fimDaSessao.ehAtual("token-bruno"))
        assertFalse(fimDaSessao.ehAtual("sessao1:ref-token-ana"))
        tokenAtual = null
        assertFalse("Ninguém logado: nenhuma sessão é a atual", fimDaSessao.ehAtual("sessao1:ref-token-bruno"))
    }

    @Test(timeout = 10_000)
    fun `leituras retidas de uma sessao anterior nao pedem para sair e entrar de novo`() {
        leituras.inserir(LeituraEntity(epc = "E1", lidoEm = agora, sessaoOperador = "sessao1:ref-token-ana"))
        servidor.enqueue(sessaoRecusada)   // lote
        servidor.enqueue(sessaoRecusada)   // E1 sozinha: retida

        val estado = sincronizador().processarPendentes()

        assertEquals(EstadoSincronizacao.AGUARDANDO_SESSAO_ANTERIOR, estado)
        assertFalse(estado.resolveEntrandoDeNovo)
        assertFalse(estado.dependeDasConfiguracoes)
        assertTrue("A faixa continua oferecendo Enviar agora", RegrasFaixaSincronizacao.acoes(1, estado).enviarAgora)
        assertTrue(SincronizacaoWorker.tentarDeNovo(estado))
        assertEquals("Nova tentativa agendada como antes", 1, novasTentativas)
        assertFalse("Continua na fila (prazo de 24 h)", leituras.porEpc("E1").envioConcluido)
    }

    @Test(timeout = 10_000)
    fun `leituras retidas da sessao atual continuam pedindo para sair e entrar de novo`() {
        leituras.inserir(LeituraEntity(epc = "E1", lidoEm = agora, sessaoOperador = "sessao1:ref-token-ana"))
        leituras.inserir(LeituraEntity(epc = "E2", lidoEm = agora, sessaoOperador = "sessao1:ref-token-bruno"))
        servidor.enqueue(sessaoRecusada)   // lote da Ana
        servidor.enqueue(sessaoRecusada)   // lote do Bruno
        servidor.enqueue(sessaoRecusada)   // E1 sozinha: retida
        servidor.enqueue(sessaoRecusada)   // E2 sozinha: retida

        assertEquals(EstadoSincronizacao.SESSAO_RECUSADA, sincronizador().processarPendentes())
    }

    @Test(timeout = 10_000)
    fun `leitura retida no proprio lote tambem conta para saber se e a sessao atual`() {
        leituras.inserir(LeituraEntity(epc = "E1", lidoEm = agora, sessaoOperador = "token-bruno"))
        servidor.enqueue(
            json(200, """{"resultados":[{"indice":0,"tag":"E1","status":"RECUSADA","mensagem":"Sessão desconhecida","motivo":"DESCONHECIDA"}]}""")
        )

        assertEquals(EstadoSincronizacao.SESSAO_RECUSADA, sincronizador().processarPendentes())
    }

    @Test(timeout = 10_000)
    fun `cadastros retidos de sessao anterior so aguardam e os da sessao atual pedem para entrar de novo`() {
        cadastros.salvar(cadastro("C1", "sessao1:ref-token-ana"))
        servidor.enqueue(sessaoRecusada)

        assertEquals(EstadoSincronizacao.AGUARDANDO_SESSAO_ANTERIOR, sincronizador(comCadastros = true).processarPendentes())

        tokenAtual = "token-ana"
        servidor.enqueue(sessaoRecusada)
        assertEquals(EstadoSincronizacao.SESSAO_RECUSADA, sincronizador(comCadastros = true).processarPendentes())
    }

    @Test
    fun `retencao da sessao atual vem antes da de sessao anterior entre as etapas`() {
        val anterior = Sincronizador.falhaDeRetencao(setOf("a"), { false }, "1 cadastro(s)", "x")
        val atual = Sincronizador.falhaDeRetencao(setOf("a", "b"), { it == "b" }, "2 leitura(s)", "x")

        assertEquals(MotivoFalha.SESSAO_ANTERIOR_RETIDA, anterior.motivo)
        assertEquals(MotivoFalha.SESSAO_INVALIDA, atual.motivo)
    }

    @Test(timeout = 10_000)
    fun `cadastro de sessao anterior e leitura da sessao atual retidos mostram sair e entrar de novo`() {
        cadastros.salvar(cadastro("C1", "sessao1:ref-token-ana"))
        leituras.inserir(LeituraEntity(epc = "E2", lidoEm = agora, sessaoOperador = "sessao1:ref-token-bruno"))
        servidor.enqueue(sessaoRecusada)   // cadastro da Ana: retido
        servidor.enqueue(sessaoRecusada)   // lote do Bruno
        servidor.enqueue(sessaoRecusada)   // E2 sozinha: retida

        assertEquals(EstadoSincronizacao.SESSAO_RECUSADA, sincronizador(comCadastros = true).processarPendentes())
    }

    // ---------- 429: coletor bloqueado por muitas tentativas ----------

    @Test(timeout = 10_000)
    fun `coletor bloqueado pelo servidor para a passada sem Enviar agora sem repetir e sem enviar os erros`() {
        leituras.inserir(LeituraEntity(epc = "E1", lidoEm = 0))
        leituras.inserir(LeituraEntity(epc = "E2", lidoEm = 0))
        servidor.enqueue(json(429, """{"status":429,"mensagem":"Muitas tentativas com chave inválida. Aguarde alguns minutos."}"""))

        val estado = sincronizador().processarPendentes()

        assertEquals(EstadoSincronizacao.SERVIDOR_BLOQUEOU, estado)
        assertEquals("Parou na primeira: as outras dariam o mesmo bloqueio", 1, servidor.requestCount)
        assertEquals("Não chama o servidor bloqueado de novo para enviar os erros", 0, enviosDeErros)
        assertTrue("Não é erro do servidor", erros.isEmpty())
        assertEquals("Sem nova tentativa daqui a 30 s", 0, novasTentativas)
        assertFalse("O Worker não fica repetindo", SincronizacaoWorker.tentarDeNovo(estado))
        val acoes = RegrasFaixaSincronizacao.acoes(2, estado)
        assertFalse(acoes.enviarAgora)
        assertTrue(acoes.chamarResponsavel)
        assertFalse(leituras.porEpc("E1").envioConcluido)
    }
}
