package com.example.rfid_server.adapter

import com.example.rfid_server.dados.ItemNaoCadastrada
import com.example.rfid_server.view.TagView
import org.junit.Assert.assertEquals
import org.junit.Test

class BuscaLeiturasTest {

    private val notebook = ItemLeitura.Cadastrada(TagView("E1", "TI-0412", "Notebook", "Dell", "Latitude", "0123/2019", "SN-8H2K"))
    private val monitor = ItemLeitura.Cadastrada(TagView("E2", "TI-0007", "Monitor", "LG", null, "PAT-1007", ""))
    private val aguardando = ItemLeitura.Aguardando("E3FF")
    private val itens = listOf(notebook, monitor, aguardando)

    @Test
    fun `busca por patrimonio tipo marca codigo e epc sem diferenciar maiusculas`() {
        assertEquals(listOf(notebook), BuscaLeituras.filtrar(itens, "0123"))
        assertEquals(listOf(monitor), BuscaLeituras.filtrar(itens, "monitor"))
        assertEquals(listOf(notebook), BuscaLeituras.filtrar(itens, " DELL "))
        assertEquals(listOf(monitor), BuscaLeituras.filtrar(itens, "ti-0007"))
        assertEquals(listOf(aguardando), BuscaLeituras.filtrar(itens, "e3f"))
        assertEquals("Vazio = tudo", itens, BuscaLeituras.filtrar(itens, "  "))
    }

    @Test
    fun `busca ignora acentos`() {
        val impressora = ItemLeitura.Cadastrada(TagView("E9", "TI-9", "Impressão", null, null, "P-9", ""))
        assertEquals(listOf(impressora), BuscaLeituras.filtrar(listOf(impressora), "impressao"))
    }

    @Test
    fun `planilha tem cadastradas aguardando e nao cadastradas`() {
        val csv = BuscaLeituras.csv(itens, listOf(ItemNaoCadastrada("E4", ItemNaoCadastrada.SEM_CADASTRO))).trim().lines()

        assertEquals("Situação;Patrimônio;Tipo;Marca;Modelo;Código interno;Nº de série;Etiqueta (EPC)", csv[0])
        assertEquals("Cadastrada;0123/2019;Notebook;Dell;Latitude;TI-0412;SN-8H2K;E1", csv[1])
        assertEquals("Aguardando o servidor;;;;;;;E3FF", csv[3])
        assertEquals("Não cadastrada;;;;;;;E4", csv[4])
    }
}
