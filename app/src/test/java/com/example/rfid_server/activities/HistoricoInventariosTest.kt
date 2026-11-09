package com.example.rfid_server.activities

import com.example.rfid_server.dados.ComparacaoInventario
import com.example.rfid_server.dados.EsperadoLocal
import com.example.rfid_server.dados.ItemEsperado
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoricoInventariosTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private val itens = listOf(ItemEsperado("E1", "P-1", "TI-1", "Notebook", "Dell", null), ItemEsperado("E2", "P-2", null, null, null, null))

    private fun resumo(id: String, finalizadoEm: Long, lidas: List<String> = listOf("E1", "X9"), comLista: Boolean = true) = ResumoFinalizacao(
        "Sala 101", finalizadoEm, "Ana", lidas.size,
        if (comLista) ComparacaoInventario.comparar(EsperadoLocal("Sala 101", true, itens = itens), lidas.toSet()) else null,
        id, lidas, if (comLista) itens else emptyList()
    )

    @Test
    fun `resumo volta igual depois de guardado`() {
        val original = resumo("inv-1", 1_000L)
        val historico = HistoricoInventarios(pasta.root)

        historico.salvar(original)

        assertEquals(original, historico.listar().single())
        assertEquals(listOf("P-2"), historico.listar().single().comparacao!!.faltando.map { it.patrimonio })
    }

    @Test
    fun `sem lista do esperado continua sem comparacao`() {
        val historico = HistoricoInventarios(pasta.root)
        historico.salvar(resumo("inv-2", 2_000L, comLista = false))

        assertNull(historico.listar().single().comparacao)
    }

    @Test
    fun `guarda so os mais novos`() {
        val historico = HistoricoInventarios(pasta.root)
        repeat(HistoricoInventarios.MAXIMO + 3) { i ->
            historico.salvar(resumo("inv-$i", i.toLong()))
            pasta.root.resolve("inv-$i.json").setLastModified(1_000_000L + i * 1000L)
        }
        val ids = historico.listar().map { it.inventarioId }
        assertEquals(HistoricoInventarios.MAXIMO, ids.size)
        assertEquals("inv-${HistoricoInventarios.MAXIMO + 2}", ids.first())
    }
}
