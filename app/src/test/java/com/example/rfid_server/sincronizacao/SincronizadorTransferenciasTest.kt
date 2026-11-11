package com.example.rfid_server.sincronizacao

import com.example.rfid_server.service.MotivoFalha
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.ResultadoTransferencia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SincronizadorTransferenciasTest {

    @get:Rule
    val pasta = TemporaryFolder()

    private var agora = 1_000_000_000_000L
    private val arquivo by lazy { pasta.root.resolve("transferencias.json") }
    private val fila by lazy { FilaTransferencias(arquivo) }

    private data class Envio(val epc: String, val local: String, val observacao: String?, val sessao: String?)
    private val enviados = mutableListOf<Envio>()
    private val erros = mutableListOf<String>()

    private fun sincronizador(resposta: () -> RespostaServidor<ResultadoTransferencia>) = SincronizadorTransferencias(
        fila,
        enviar = { epc, local, observacao, sessao -> enviados += Envio(epc, local, observacao, sessao); resposta() },
        registrarErro = { _, m -> erros += m },
        relogio = { agora }
    )

    private fun aceita(guia: Long?, jaEstava: Boolean = false) =
        RespostaServidor.Sucesso(ResultadoTransferencia("SALA 12", "EM_USO", "Em uso", guia, jaEstava))

    @Test
    fun `pedir de novo substitui o pedido e a fila sobrevive a reabrir o app`() {
        fila.adicionar("inv-1", "E1", "SALA 12", "sessao1:x", agora, "primeira")
        fila.adicionar("inv-1", "E1", "SALA 12", "sessao1:x", agora + 1, "  segunda  ")
        fila.adicionar("inv-1", "E2", "SALA 12", null, agora, "   ")

        val reaberta = FilaTransferencias(arquivo).doInventario("inv-1")
        assertEquals(2, reaberta.size)
        assertEquals("segunda", reaberta.getValue("E1").observacao)
        assertNull("Observação em branco não vai", reaberta.getValue("E2").observacao)
        assertEquals(FilaTransferencias.Estado.PENDENTE, reaberta.getValue("E1").estado)
    }

    @Test
    fun `aceita fica feita com a guia e vai com o local e a sessao de quem pediu`() {
        fila.adicionar("inv-1", "E1", "SALA 12", "sessao1:ana", agora, "mudou de sala")

        assertNull(sincronizador { aceita(guia = 57) }.processar())

        assertEquals(Envio("E1", "SALA 12", "mudou de sala", "sessao1:ana"), enviados.single())
        val item = FilaTransferencias(arquivo).doInventario("inv-1").getValue("E1")
        assertEquals(FilaTransferencias.Estado.FEITA, item.estado)
        assertEquals(57L, item.guia)
        assertTrue(fila.pendentes().isEmpty())
        assertTrue(erros.isEmpty())

        // Respondida não vai de novo
        sincronizador { aceita(guia = 58) }.processar()
        assertEquals(1, enviados.size)
    }

    @Test
    fun `ja estava no local fica feita sem guia`() {
        fila.adicionar("inv-1", "E1", "SALA 12", null, agora)
        sincronizador { aceita(guia = null, jaEstava = true) }.processar()

        val item = fila.doInventario("inv-1").getValue("E1")
        assertEquals(FilaTransferencias.Estado.FEITA, item.estado)
        assertNull(item.guia)
        assertTrue(item.jaEstava)
    }

    @Test
    fun `recusa do servidor fica recusada com o motivo e vai para os erros`() {
        fila.adicionar("inv-1", "E1", "SALA 12", null, agora)

        assertNull(sincronizador { RespostaServidor.Falha(MotivoFalha.REQUISICAO_RECUSADA, "Bem baixado não pode ser transferido") }.processar())

        val item = fila.doInventario("inv-1").getValue("E1")
        assertEquals(FilaTransferencias.Estado.RECUSADA, item.estado)
        assertEquals("Bem baixado não pode ser transferido", item.mensagem)
        assertEquals(1, erros.size)

        // Pedir de novo (ex.: depois de corrigir o local na web) volta a ser pendente
        fila.adicionar("inv-1", "E1", "SALA 12", null, agora + 1)
        assertEquals(1, fila.pendentes().size)
    }

    @Test
    fun `sem rede para a passada e o pedido fica`() {
        fila.adicionar("inv-1", "E1", "SALA 12", null, agora)
        fila.adicionar("inv-1", "E2", "SALA 12", null, agora)
        val semRede = RespostaServidor.Falha(MotivoFalha.SEM_CONEXAO, "Sem conexão")

        assertSame(semRede, sincronizador { semRede }.processar())

        assertEquals("Para no primeiro envio", 1, enviados.size)
        assertEquals(2, fila.pendentes().size)
    }

    @Test
    fun `erro do servidor fica pendente para a proxima passada`() {
        fila.adicionar("inv-1", "E1", "SALA 12", null, agora)

        sincronizador { RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "HTTP 500") }.processar()

        assertEquals(1, fila.pendentes().size)
        assertTrue(erros.isEmpty())
    }

    @Test
    fun `sessao encerrada fica recusada e deixa pedir de novo`() {
        // Antes ficava "aguardando envio" 7 dias: com a sessão de quem pediu encerrada o servidor nunca aceita
        fila.adicionar("inv-1", "E1", "SALA 12", "token-velho", agora)

        sincronizador { RespostaServidor.Falha(MotivoFalha.SESSAO_INVALIDA, "Sessão encerrada") }.processar()

        val item = fila.doInventario("inv-1").getValue("E1")
        assertEquals(FilaTransferencias.Estado.RECUSADA, item.estado)
        assertTrue(fila.pendentes().isEmpty())
    }

    @Test
    fun `erros seguidos do servidor param a etapa`() {
        for (i in 1..5) fila.adicionar("inv-1", "E$i", "SALA 12", null, agora)

        sincronizador { RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "HTTP 500") }.processar()

        // Para no limite de erros seguidos em vez de esperar o tempo limite de cada pedido
        assertEquals(RegraFalhas.LIMITE_ERROS_SEGUIDOS, enviados.size)
        assertEquals(5, fila.pendentes().size)
    }

    @Test
    fun `resposta perdida vai de novo depois sem problema`() {
        // O servidor gravou, mas a rede caiu antes da resposta: o pedido continua pendente e o reenvio diz "já estava"
        fila.adicionar("inv-1", "E1", "SALA 12", null, agora)
        sincronizador { RespostaServidor.Falha(MotivoFalha.SEM_CONEXAO, "Tempo esgotado") }.processar()
        sincronizador { aceita(guia = null, jaEstava = true) }.processar()

        assertEquals(2, enviados.size)
        assertEquals(FilaTransferencias.Estado.FEITA, fila.doInventario("inv-1").getValue("E1").estado)
    }

    @Test
    fun `pedido de mais de 7 dias sai e o pendente vira erro`() {
        fila.adicionar("inv-1", "E1", "SALA 12", null, agora)
        fila.adicionar("inv-1", "E2", "SALA 12", null, agora)
        sincronizador { RespostaServidor.Falha(MotivoFalha.ERRO_SERVIDOR, "HTTP 500") }.processar()
        fila.concluir(fila.pendentes().first { it.epc == "E2" }, 9, false)

        agora += SincronizadorTransferencias.PRAZO_MS + 1
        enviados.clear()
        sincronizador { aceita(guia = 1) }.processar()

        assertTrue(fila.doInventario("inv-1").isEmpty())
        assertTrue(enviados.isEmpty())
        assertEquals("Só o pendente conta como descartado", 1, erros.size)
    }

    @Test
    fun `arquivo ilegivel recomeca vazio`() {
        arquivo.writeText("{quebrado")
        assertTrue(fila.pendentes().isEmpty())
        fila.adicionar("inv-1", "E1", "SALA 12", null, agora)
        assertEquals(1, fila.pendentes().size)
    }
}
