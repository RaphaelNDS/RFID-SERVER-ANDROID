package com.example.rfid_server.dados

import com.example.rfid_server.SessaoOperador
import com.example.rfid_server.sincronizacao.FilaTransferencias
import com.example.rfid_server.view.TagView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quando a aba Lidas oferece "Transferir para este local" e o podeTransferir do login. */
class TransferenciaNaLidaTest {

    private fun ficha(situacao: String? = "EM_USO") =
        TagView("E1", "TI-1", "Notebook", "Dell", "Latitude", "0123/2019", "SN1", situacao, situacao)

    private fun pedido(estado: FilaTransferencias.Estado) =
        FilaTransferencias.Item("inv-1", "E1", "SALA 12", null, 1L, estado = estado)

    private val fora = LidaInventario("E1", null, foraDaLista = true, ficha = ficha())

    @Test
    fun `fora da lista com cadastro em uso ou extraviado pode transferir`() {
        assertTrue(fora.podePedirTransferencia)
        assertTrue("Achar o extraviado na sala é o caso de transferir", fora.copy(ficha = ficha("EXTRAVIADO")).podePedirTransferencia)
    }

    @Test
    fun `sem cadastro consultado baixado ou esperada no local nao pode`() {
        assertFalse("Ficha ainda não consultada", fora.copy(ficha = null).podePedirTransferencia)
        assertFalse(fora.copy(ficha = null, semCadastro = true).podePedirTransferencia)
        assertFalse("O servidor recusa bem baixado (409)", fora.copy(ficha = ficha("BAIXADO")).podePedirTransferencia)
        assertFalse(LidaInventario("E1", "0123/2019", foraDaLista = false, ficha = ficha()).podePedirTransferencia)
    }

    @Test
    fun `pendente ou feita nao oferece de novo mas recusada pode tentar de novo`() {
        assertFalse(fora.copy(transferencia = pedido(FilaTransferencias.Estado.PENDENTE)).podePedirTransferencia)
        assertFalse(fora.copy(transferencia = pedido(FilaTransferencias.Estado.FEITA)).podePedirTransferencia)
        assertTrue(fora.copy(transferencia = pedido(FilaTransferencias.Estado.RECUSADA)).podePedirTransferencia)
    }

    @Test
    fun `comFichas junta a transferencia so nas fora da lista`() {
        val lidas = listOf(LidaInventario("E1", null, foraDaLista = true), LidaInventario("E2", "P2", foraDaLista = false))
        val pedidos = mapOf("E1" to pedido(FilaTransferencias.Estado.PENDENTE), "E2" to pedido(FilaTransferencias.Estado.PENDENTE))

        val juntas = LidaInventario.comFichas(lidas, emptyMap(), emptySet(), pedidos)

        assertEquals(FilaTransferencias.Estado.PENDENTE, juntas[0].transferencia?.estado)
        assertEquals(null, juntas[1].transferencia)
    }

    @Test
    fun `login traz podeTransferir e servidor antigo que nao manda e nao`() {
        val base = """"token":"t","expiraEm":"2026-11-10T20:00:00-03:00","nome":"Ana","email":"a@x","podeLer":true,"podeCadastrar":true"""
        assertTrue(SessaoOperador.deJson("{$base,\"podeTransferir\":true}").podeTransferir)
        assertFalse(SessaoOperador.deJson("{$base}").podeTransferir)
    }
}
