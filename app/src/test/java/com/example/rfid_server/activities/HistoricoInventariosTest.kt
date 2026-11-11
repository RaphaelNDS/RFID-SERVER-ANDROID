package com.example.rfid_server.activities

import com.example.rfid_server.dados.ComparacaoInventario
import com.example.rfid_server.dados.EsperadoLocal
import com.example.rfid_server.dados.ItemEsperado
import com.example.rfid_server.sincronizacao.FilaJustificativas.Motivo
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
    fun `alertas do cadastro voltam iguais do historico`() {
        val original = resumo("inv-a", 3_000L).copy(alertas = mapOf("X9" to "Patrimônio PAT-77 (Baixado)"))
        val historico = HistoricoInventarios(pasta.root)

        historico.salvar(original)

        assertEquals(original, historico.listar().single())
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

    @Test
    fun `justificativas ficam guardadas mesmo depois que a fila esquece`() {
        val historico = HistoricoInventarios(pasta.root)
        historico.salvar(resumo("inv-j", 5_000L))

        historico.justificar("inv-j", listOf("E2"), Motivo.EMPRESTADO)
        historico.justificar("inv-j", listOf("E2"), Motivo.MANUTENCAO)

        assertEquals(mapOf("E2" to Motivo.MANUTENCAO), HistoricoInventarios(pasta.root).motivos("inv-j"))
        // Guardar o resumo de novo não apaga as justificativas
        historico.salvar(resumo("inv-j", 5_000L))
        assertEquals(mapOf("E2" to Motivo.MANUTENCAO), historico.motivos("inv-j"))
    }

    @Test
    fun `observacao do outro fica guardada e sai ao trocar o motivo`() {
        val historico = HistoricoInventarios(pasta.root)
        historico.salvar(resumo("inv-o", 5_000L))

        historico.justificar("inv-o", listOf("E2"), Motivo.OUTRO, "  levado para a obra ")
        assertEquals(mapOf("E2" to "levado para a obra"), historico.observacoes("inv-o"))
        // Guardar o resumo de novo mantém a observação
        historico.salvar(resumo("inv-o", 5_000L))
        assertEquals(mapOf("E2" to "levado para a obra"), historico.observacoes("inv-o"))

        historico.justificar("inv-o", listOf("E2"), Motivo.EMPRESTADO)
        assertEquals(emptyMap<String, String>(), historico.observacoes("inv-o"))
        assertEquals(mapOf("E2" to Motivo.EMPRESTADO), historico.motivos("inv-o"))
    }

    @Test
    fun `lista com as justificadas de cada um, do mais novo para o mais antigo`() {
        val historico = HistoricoInventarios(pasta.root)
        historico.salvar(resumo("inv-a", finalizadoEm = 1_000))
        historico.salvar(resumo("inv-b", finalizadoEm = 2_000))
        historico.justificar("inv-a", listOf("E2"), Motivo.EMPRESTADO)

        val lista = historico.listarComJustificadas()

        assertEquals(listOf("inv-b", "inv-a"), lista.map { it.first.inventarioId })
        assertEquals(setOf("E2"), lista.single { it.first.inventarioId == "inv-a" }.second)
        assertEquals(emptySet<String>(), lista.single { it.first.inventarioId == "inv-b" }.second)
    }

    @Test
    fun `justificar inventario fora do historico nao cria arquivo`() {
        val historico = HistoricoInventarios(pasta.root)
        historico.justificar("inv-x", listOf("E1"), Motivo.BAIXADO)

        assertEquals(emptyMap<String, Motivo>(), historico.motivos("inv-x"))
        assertEquals(emptyList<ResumoFinalizacao>(), historico.listar())
    }

    @Test
    fun `ordem e limpeza pela data de finalizacao e nao pela alteracao do arquivo`() {
        val historico = HistoricoInventarios(pasta.root)
        historico.salvar(resumo("antigo", 1_000L))
        historico.salvar(resumo("novo", 9_000L))
        // Justificar o antigo depois mexe no arquivo dele, mas ele continua sendo o mais antigo
        pasta.root.resolve("novo.json").setLastModified(1_000_000L)
        historico.justificar("antigo", listOf("E2"), Motivo.OUTRO)
        pasta.root.resolve("antigo.json").setLastModified(2_000_000L)

        assertEquals(listOf("novo", "antigo"), historico.listar().map { it.inventarioId })
    }
}
