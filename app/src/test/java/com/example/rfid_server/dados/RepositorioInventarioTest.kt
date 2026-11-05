package com.example.rfid_server.dados

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executor

class RepositorioInventarioTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val dao = FakeInventarioDao()
    private var sincronizacoes = 0
    private var agora = 1_000L
    private var proximoId = 1
    private var sessao: String? = "sessao-ana"

    private fun repositorio() = RepositorioInventario(
        dao = dao,
        executor = Executor { it.run() },
        solicitarSincronizacao = { sincronizacoes++ },
        relogio = { agora },
        gerarId = { "inv-${proximoId++}" },
        sessaoAtual = { sessao }
    )

    @Test
    fun `abrir cria o inventario com o local limpo e pede sincronizacao`() {
        var resultado: Boolean? = null
        agora = 5_000L

        repositorio().abrir("  Sala   101 ") { resultado = it }

        assertEquals(true, resultado)
        val inv = dao.inventario("inv-1")
        assertEquals("Sala 101", inv.local)
        assertEquals(5_000L, inv.iniciadoEm)
        assertNull(inv.finalizadoEm)
        assertFalse(inv.aberturaEnviada)
        assertEquals(1, sincronizacoes)
    }

    @Test
    fun `nao abre um segundo inventario nem com local vazio`() {
        val repositorio = repositorio()
        val resultados = mutableListOf<Boolean>()

        repositorio.abrir("   ") { resultados += it }
        repositorio.abrir("Sala 101") { resultados += it }
        repositorio.abrir("Almoxarifado") { resultados += it }

        assertEquals(listOf(false, true, false), resultados)
        assertEquals(1, dao.inventarios.size)
    }

    @Test
    fun `registrar guarda cada etiqueta uma vez com a hora da leitura`() {
        val repositorio = repositorio()
        repositorio.abrir("Sala 101")
        agora = 7_000L

        repeat(3) { repositorio.registrar("E1") }
        repositorio.registrar("E2")

        assertEquals(listOf("E1", "E2"), dao.itens.map { it.epc })
        assertEquals(7_000L, dao.itens.first().lidoEm)
        assertEquals("1 da abertura + 2 etiquetas novas", 3, sincronizacoes)
    }

    @Test
    fun `aviso de etiqueta nova so toca na primeira leitura e com inventario aberto`() {
        val repositorio = repositorio()
        var avisos = 0

        repositorio.registrar("E0") { avisos++ }   // sem inventário aberto: ignorada, sem aviso
        repositorio.abrir("Sala 101")
        repeat(3) { repositorio.registrar("E1") { avisos++ } }
        repositorio.registrar("E2") { avisos++ }

        assertEquals(2, avisos)
    }

    @Test
    fun `leitura sem inventario aberto e ignorada`() {
        repositorio().registrar("E1")

        assertTrue(dao.itens.isEmpty())
        assertEquals(0, sincronizacoes)
    }

    @Test
    fun `finalizar fecha o inventario informa o total e libera para abrir outro`() {
        val repositorio = repositorio()
        repositorio.abrir("Sala 101")
        repositorio.registrar("E1")
        repositorio.registrar("E2")
        agora = 9_000L
        var total: Int? = -1

        repositorio.finalizar { total = it }

        assertEquals(2, total)
        assertEquals(9_000L, dao.inventario("inv-1").finalizadoEm)

        repositorio.registrar("E3")
        assertEquals("Depois de finalizado, novas leituras não entram", 2, dao.itens.size)

        var abriu = false
        repositorio.abrir("Almoxarifado") { abriu = it }
        assertTrue(abriu)
    }

    @Test
    fun `finalizar sem operador logado nao finaliza e o inventario continua aberto`() {
        val repositorio = repositorio()
        repositorio.abrir("Sala 101")
        repositorio.registrar("E1")
        val pedidosAntes = sincronizacoes
        sessao = null   // sessão expirou com a tela aberta
        var total: Int? = -1

        repositorio.finalizar { total = it }

        assertNull("A tela manda entrar de novo", total)
        assertNull(dao.inventario("inv-1").finalizadoEm)
        assertEquals(pedidosAntes, sincronizacoes)

        // Depois de entrar de novo, finaliza com a sessão nova
        sessao = "sessao-ana-2"
        repositorio.finalizar { total = it }
        assertEquals(1, total)
        assertEquals("sessao-ana-2", dao.inventario("inv-1").sessaoFinalizacao)
    }

    @Test
    fun `finalizar sem inventario aberto devolve null`() {
        var total: Int? = -1

        repositorio().finalizar { total = it }

        assertNull(total)
    }

    @Test
    fun `inventario em andamento e retomado quando o app reabre`() {
        // Primeira "execução" do app: abre e lê
        val antes = repositorio()
        antes.abrir("Sala 101")
        antes.registrar("E1")

        // App fechado e aberto de novo: novo repositório sobre o mesmo banco
        val depois = repositorio()
        depois.registrar("E1")
        depois.registrar("E2")

        assertEquals(listOf("E1", "E2"), dao.itens.map { it.epc })
        assertEquals("inv-1", dao.itens.last().inventarioId)
        assertEquals("Sala 101", depois.inventarioAberto.value?.local)
    }

    // ---------- Cancelar ----------

    @Test
    fun `cancelar antes de qualquer envio apaga o inventario e as etiquetas e permite abrir outro`() {
        val repositorio = repositorio()
        repositorio.abrir("Sala 11")
        repositorio.registrar("E1")
        repositorio.registrar("E2")
        var resultado: RepositorioInventario.ResultadoCancelamento? = null

        repositorio.cancelar { resultado = it }

        assertEquals(RepositorioInventario.ResultadoCancelamento.CANCELADO, resultado)
        assertTrue(dao.inventarios.isEmpty() && dao.itens.isEmpty())
        assertNull(repositorio.inventarioAberto.value)

        var abriu: Boolean? = null
        repositorio.abrir("Sala 101") { abriu = it }
        assertEquals(true, abriu)
        repositorio.registrar("E1")
        assertEquals("E1 lida de novo no inventário novo", listOf("inv-2"), dao.itens.map { it.inventarioId })
    }

    @Test
    fun `nao cancela depois que a abertura foi enviada`() {
        val repositorio = repositorio()
        repositorio.abrir("Sala 101")
        repositorio.registrar("E1")
        dao.definirAberturaEnviada("inv-1", true)
        var resultado: RepositorioInventario.ResultadoCancelamento? = null

        repositorio.cancelar { resultado = it }

        assertEquals(RepositorioInventario.ResultadoCancelamento.JA_ENVIADO, resultado)
        assertEquals(1, dao.inventarios.size)
        assertEquals(1, dao.itens.size)
    }

    @Test
    fun `nao cancela se alguma etiqueta ja saiu da fila, mesmo com a abertura voltando para a fila`() {
        val repositorio = repositorio()
        repositorio.abrir("Sala 101")
        repositorio.registrar("E1")
        dao.marcarItensEnviados(dao.itens.map { it.id })
        var resultado: RepositorioInventario.ResultadoCancelamento? = null

        repositorio.cancelar { resultado = it }

        assertEquals(RepositorioInventario.ResultadoCancelamento.JA_ENVIADO, resultado)
        assertEquals(1, dao.inventarios.size)
    }

    @Test
    fun `cancelar sem inventario aberto nao faz nada`() {
        var resultado: RepositorioInventario.ResultadoCancelamento? = null

        repositorio().cancelar { resultado = it }

        assertEquals(RepositorioInventario.ResultadoCancelamento.SEM_INVENTARIO, resultado)
    }

    // ---------- Local novo ----------

    @Test
    fun `local novo e o que nao esta nas sugestoes, sem diferenciar maiusculas e espacos`() {
        val conhecidos = listOf("Sala 101", "Almoxarifado")

        assertFalse(RepositorioInventario.localNovo("  sala   101 ", conhecidos))
        assertFalse(RepositorioInventario.localNovo("ALMOXARIFADO", conhecidos))
        assertTrue(RepositorioInventario.localNovo("Sala 11", conhecidos))
        assertFalse("Sem nenhum local conhecido não há com o que comparar", RepositorioInventario.localNovo("Sala 11", emptyList()))
    }

    // ---------- Avisos de recusa ----------

    private fun recusarInteiro(id: String, local: String, motivo: String, quando: Long) {
        dao.inserirInventario(InventarioEntity(id, local, 1_000))
        dao.registrarRecusa(id, motivo, quando)
        dao.desistir(id, quando)
    }

    @Test
    fun `aviso de inventario recusado aparece ate o operador marcar ciente, e continua escondido ao reabrir`() {
        agora = RepositorioInventario.GUARDA_AVISO_MS + 100_000
        val cientes = RepositorioInventario.GuardaCientes.EmMemoria()
        val repositorio = RepositorioInventario(dao, { it.run() }, {}, { agora }, sessaoAtual = { sessao }, cientes = cientes)
        val avisos = repositorio.avisosRecusa.also { it.observeForever { } }
        assertEquals(emptyList<RecusaInventario>(), avisos.value)

        recusarInteiro("inv-a", "Sala 101", "Local inativo", agora)
        recusarInteiro("antigo", "Sala 9", "Antigo", 1_000)

        val aviso = avisos.value!!.single()
        assertEquals(RecusaInventario("inv-a", "Sala 101", "Local inativo", 0), aviso)

        repositorio.marcarCiente(aviso)
        assertEquals(emptyList<RecusaInventario>(), avisos.value)
        assertEquals(setOf("inventario:inv-a"), cientes.chaves)

        // App aberto de novo: o "ciente" continua valendo
        val depois = RepositorioInventario(dao, { it.run() }, {}, { agora }, cientes = cientes).avisosRecusa.also { it.observeForever { } }
        assertEquals(emptyList<RecusaInventario>(), depois.value)
    }

    @Test
    fun `aviso de etiquetas desistidas volta se o coletor desistir de mais etiquetas`() {
        val cientes = RepositorioInventario.GuardaCientes.EmMemoria(setOf("inventario:apagado-ha-tempo"))
        val repositorio = RepositorioInventario(dao, { it.run() }, {}, { agora }, sessaoAtual = { sessao }, cientes = cientes)
        val avisos = repositorio.avisosRecusa.also { it.observeForever { } }
        repositorio.abrir("Sala 101")
        repositorio.registrar("E1")
        repositorio.registrar("E2")
        val (e1, e2) = dao.itens.map { it.id }

        dao.registrarRecusaItens(listOf(e1), "Sessão desativada", agora)
        dao.desistirItens(listOf(e1), 1, agora)
        val primeiro = avisos.value!!.single()
        assertEquals(1, primeiro.etiquetas)
        repositorio.marcarCiente(primeiro)
        assertTrue(avisos.value!!.isEmpty())
        assertEquals("Chaves de avisos que não existem mais saem da lista", setOf(primeiro.chave()), cientes.chaves)

        dao.registrarRecusaItens(listOf(e2), "Sessão desativada", agora)
        dao.desistirItens(listOf(e2), 1, agora)
        assertEquals(2, avisos.value!!.single().etiquetas)
    }
}
