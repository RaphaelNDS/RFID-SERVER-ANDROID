package com.example.rfid_server.adapter

import com.example.rfid_server.dados.LeituraEntity
import com.example.rfid_server.dados.RecusaInventario
import com.example.rfid_server.dados.Situacao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ItemLeituraTest {

    private fun cadastrada(id: Long, epc: String) = LeituraEntity(
        id = id, epc = epc, lidoEm = 0, situacao = Situacao.CADASTRADA,
        codigoInterno = "CI-$epc", patrimonio = "PAT-$epc", numeroSerie = "SN-$epc"
    )

    private fun aguardando(id: Long, epc: String) = LeituraEntity(id = id, epc = epc, lidoEm = 0)

    @Test
    fun `junta cadastradas e as que aguardam o servidor da mais nova para a mais antiga`() {
        val itens = ItemLeitura.montar(
            cadastradas = listOf(cadastrada(3, "C3"), cadastrada(1, "C1")),
            aguardando = listOf(aguardando(4, "P4"), aguardando(2, "P2"))
        )

        assertEquals(listOf("a:P4", "c:C3", "a:P2", "c:C1"), itens.map { it.chave })
        assertEquals("PAT-C3", (itens[1] as ItemLeitura.Cadastrada).tag.patrimonio)
        assertEquals(ItemLeitura.Aguardando("P4"), itens[0])
    }

    @Test
    fun `a mesma etiqueta muda de chave quando o servidor identifica (a linha e trocada)`() {
        val antes = ItemLeitura.montar(emptyList(), listOf(aguardando(1, "E1"))).single()
        val depois = ItemLeitura.montar(listOf(cadastrada(1, "E1")), emptyList()).single()

        assertNotEquals(antes.chave, depois.chave)
    }

    @Test
    fun `avisos de recusa ja vistos nao aparecem e etiquetas novas desistidas geram outra chave`() {
        val inteiro = RecusaInventario("inv-1", "Sala 101", "Local inativo", 0)
        val umaEtiqueta = RecusaInventario("inv-2", "Sala 102", "Sessão", 1)
        val duasEtiquetas = umaEtiqueta.copy(etiquetas = 2)

        assertEquals("inventario:inv-1", inteiro.chave())
        assertEquals(
            listOf(duasEtiquetas),
            RecusaInventario.naoVistos(listOf(inteiro, duasEtiquetas), setOf(inteiro.chave(), umaEtiqueta.chave()))
        )
    }
}
