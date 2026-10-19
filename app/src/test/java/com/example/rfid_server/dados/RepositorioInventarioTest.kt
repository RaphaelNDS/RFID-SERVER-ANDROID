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
}
