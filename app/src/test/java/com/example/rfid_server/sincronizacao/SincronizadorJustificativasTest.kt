package com.example.rfid_server.sincronizacao

import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.ResultadoJustificativas
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SincronizadorJustificativasTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private var agora = 1_000_000_000_000L
    private val fila by lazy { FilaJustificativas(pasta.root.resolve("justificativas.json")) }
    private val enviados = mutableListOf<Triple<String, JSONArray, String?>>()
    private val erros = mutableListOf<String>()

    private fun sincronizador(resposta: () -> RespostaServidor<ResultadoJustificativas>) = SincronizadorJustificativas(
        fila,
        enviar = { id, itens, sessao -> enviados += Triple(id, itens, sessao); resposta() },
        registrarErro = { _, m -> erros += m },
        relogio = { agora }
    )

    @Test
    fun `justificar de novo troca o motivo e a fila sobrevive a reabrir o app`() {
        fila.adicionar("inv-1", listOf("E1", "E2"), FilaJustificativas.Motivo.EMPRESTADO, "sessao1:x", agora)
        fila.adicionar("inv-1", listOf("E2"), FilaJustificativas.Motivo.BAIXADO, "sessao1:x", agora)

        val reaberta = FilaJustificativas(pasta.root.resolve("justificativas.json"))
        assertEquals(mapOf("E1" to FilaJustificativas.Motivo.EMPRESTADO, "E2" to FilaJustificativas.Motivo.BAIXADO), reaberta.motivos("inv-1"))
    }

    @Test
    fun `aceitas saem da fila e vao com a sessao de quem justificou`() {
        fila.adicionar("inv-1", listOf("E1"), FilaJustificativas.Motivo.MANUTENCAO, "sessao1:ana", agora)

        assertNull(sincronizador { RespostaServidor.Sucesso(ResultadoJustificativas(1, emptyList())) }.processar())

        assertTrue(fila.pendentes().isEmpty())
        val (id, itens, sessao) = enviados.single()
        assertEquals("inv-1", id)
        assertEquals("sessao1:ana", sessao)
        assertEquals("MANUTENCAO", itens.getJSONObject(0).getString("motivo"))
        assertEquals("E1", itens.getJSONObject(0).getString("tag"))
    }

    @Test
    fun `servidor sem a rota guarda e descarta depois do prazo`() {
        fila.adicionar("inv-1", listOf("E1"), FilaJustificativas.Motivo.OUTRO, null, agora)

        sincronizador { RespostaServidor.NaoEncontrado }.processar()
        assertEquals(1, fila.pendentes().size)

        agora += SincronizadorJustificativas.PRAZO_MS + 1
        sincronizador { RespostaServidor.NaoEncontrado }.processar()
        assertTrue(fila.pendentes().isEmpty())
        assertEquals(1, erros.size)
    }

    @Test
    fun `recusa definitiva sai da fila e falha do ambiente para a passada`() {
        fila.adicionar("inv-1", listOf("E1"), FilaJustificativas.Motivo.OUTRO, null, agora)
        val semRede = RespostaServidor.Falha(MotivoFalha.SEM_CONEXAO, "Sem conexão com o servidor")

        assertEquals(semRede, sincronizador { semRede }.processar())
        assertEquals("Sem rede: continua guardada", 1, fila.pendentes().size)

        sincronizador { RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, "Inventário de outro coletor") }.processar()
        assertTrue(fila.pendentes().isEmpty())
    }

    @Test
    fun `observacao do motivo Outro vai junto e sobrevive a reabrir o app`() {
        fila.adicionar("inv-1", listOf("E9"), FilaJustificativas.Motivo.OUTRO, null, agora, "  levado para a sala 204 ")
        assertEquals("levado para a sala 204", FilaJustificativas(pasta.root.resolve("justificativas.json")).pendentes().single().observacao)

        sincronizador { RespostaServidor.Sucesso(ResultadoJustificativas(1, emptyList())) }.processar()

        assertEquals("levado para a sala 204", enviados.single().second.getJSONObject(0).getString("observacao"))
    }
}
