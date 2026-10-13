package com.example.rfid_server.dados

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.rfid_server.SessaoOperador
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.OffsetDateTime
import java.util.concurrent.Executor

/** Login de operador no app: cada registro guarda a sessão de quem fez, e sem operador nada é registrado. */
class AutoriaOperadorTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private var sessao: String? = "sessao-ana"
    private val direto = Executor { it.run() }

    // ---------- SessaoOperador ----------

    @Test
    fun `sessao do servidor e lida com a expiracao em epoch`() {
        val json = """
            {"token":"tok-123","expiraEm":"2026-10-14T02:00:00-03:00","nome":"Ana","email":"ana@x.com",
             "podeLer":true,"podeCadastrar":false,"senhaFraca":true}
        """

        val s = SessaoOperador.deJson(json)

        assertEquals("tok-123", s.token)
        assertEquals(OffsetDateTime.parse("2026-10-14T02:00:00-03:00").toInstant().toEpochMilli(), s.expiraEm)
        assertEquals("Ana", s.nome)
        assertFalse(s.podeCadastrar)
        assertTrue(s.senhaFraca)
    }

    @Test
    fun `sessao vale ate o instante da expiracao`() {
        val s = SessaoOperador("t", expiraEm = 10_000, nome = "Ana", email = "a@x", podeLer = true, podeCadastrar = false)

        assertTrue(s.validaEm(9_999))
        assertFalse(s.validaEm(10_000))
    }

    // ---------- Leituras ----------

    private fun leituras(dao: FakeLeituraDao) =
        RepositorioLeituras(dao, direto, solicitarSincronizacao = {}, sessaoAtual = { sessao })

    @Test
    fun `leitura guarda a sessao do operador que leu`() {
        val dao = FakeLeituraDao()
        val repositorio = leituras(dao)

        repositorio.registrar("E1")
        sessao = "sessao-bruno" // troca de operador no meio
        repositorio.registrar("E2")

        assertEquals(listOf("sessao-ana", "sessao-bruno"), dao.leituras.map { it.sessaoOperador })
    }

    @Test
    fun `sem operador logado a leitura e ignorada`() {
        val dao = FakeLeituraDao()
        sessao = null

        leituras(dao).registrar("E1")

        assertTrue(dao.leituras.isEmpty())
    }

    // ---------- Inventário ----------

    private fun inventario(dao: FakeInventarioDao) =
        RepositorioInventario(dao, direto, solicitarSincronizacao = {}, gerarId = { "inv-1" }, sessaoAtual = { sessao })

    @Test
    fun `inventario guarda quem abriu quem leu cada etiqueta e quem finalizou`() {
        val dao = FakeInventarioDao()
        val repositorio = inventario(dao)

        repositorio.abrir("Sala 101")
        repositorio.registrar("E1")
        sessao = "sessao-bruno" // outro operador continua o mesmo inventário
        repositorio.registrar("E2")
        repositorio.finalizar()

        val inv = dao.inventario("inv-1")
        assertEquals("sessao-ana", inv.sessaoOperador)
        assertEquals("sessao-bruno", inv.sessaoFinalizacao)
        assertEquals(listOf("sessao-ana", "sessao-bruno"), dao.itens.map { it.sessaoOperador })
    }

    @Test
    fun `sem operador logado nao abre inventario nem registra etiqueta`() {
        val dao = FakeInventarioDao()
        sessao = null
        var abriu: Boolean? = null

        inventario(dao).abrir("Sala 101") { abriu = it }

        assertEquals(false, abriu)
        assertTrue(dao.inventarios.isEmpty())
    }

    // ---------- Cadastros ----------

    @Test
    fun `cadastro guarda a sessao e sem operador nao e guardado`() {
        val dao = FakeCadastroDao()
        val repositorio = RepositorioCadastros(dao, direto, solicitarSincronizacao = {}, sessaoAtual = { sessao })
        val resultados = mutableListOf<Boolean>()
        val cadastro = CadastroEntity(epc = "E1", codigoInterno = "TI", criadoEm = 0)

        repositorio.salvar(cadastro) { resultados += it }
        assertEquals("sessao-ana", dao.porEpcAgora("E1")?.sessaoOperador)

        sessao = null
        repositorio.salvar(cadastro.copy(epc = "E2")) { resultados += it }

        assertEquals(listOf(true, false), resultados)
        assertEquals(null, dao.porEpcAgora("E2"))
    }
}
