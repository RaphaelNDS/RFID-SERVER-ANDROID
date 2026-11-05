package com.example.rfid_server.activities

import com.example.rfid_server.sincronizacao.EstadoSincronizacao
import org.junit.Assert.assertEquals
import org.junit.Test

class RegrasFaixaSincronizacaoTest {

    private val nenhuma = RegrasFaixaSincronizacao.Acoes(enviarAgora = false, chamarResponsavel = false)
    private val enviar = RegrasFaixaSincronizacao.Acoes(enviarAgora = true, chamarResponsavel = false)
    private val responsavel = RegrasFaixaSincronizacao.Acoes(enviarAgora = false, chamarResponsavel = true)

    @Test
    fun `texto da faixa poe o relogio primeiro, depois o responsavel e por ultimo as pendencias`() {
        // O relógio errado é o que faz o servidor recusar: não pode ficar no fim, onde o corte o escondia
        assertEquals(
            "Relógio errado. Chame o responsável. Sem chave — 3 leitura(s).",
            RegrasFaixaSincronizacao.texto("Relógio errado.", "Sem chave — 3 leitura(s).", "Chame o responsável.")
        )
        assertEquals("Relógio errado. Enviando — 2", RegrasFaixaSincronizacao.texto("Relógio errado.", "Enviando — 2", null))
        assertEquals("Chame o responsável. Sem chave — 1", RegrasFaixaSincronizacao.texto(null, "Sem chave — 1", "Chame o responsável."))
        assertEquals("Relógio errado", RegrasFaixaSincronizacao.texto("Relógio errado", null, null))
        assertEquals("Enviando — 2", RegrasFaixaSincronizacao.texto(null, "Enviando — 2", null))
    }

    @Test
    fun `sem leituras aguardando nao oferece acao (so o aviso do relogio)`() {
        EstadoSincronizacao.entries.forEach { assertEquals(nenhuma, RegrasFaixaSincronizacao.acoes(0, it)) }
        assertEquals(nenhuma, RegrasFaixaSincronizacao.acoes(0, null))
    }

    @Test
    fun `problema que repetir pode resolver oferece enviar agora`() {
        assertEquals(enviar, RegrasFaixaSincronizacao.acoes(3, EstadoSincronizacao.SEM_CONEXAO))
        assertEquals(enviar, RegrasFaixaSincronizacao.acoes(3, EstadoSincronizacao.ERRO_SERVIDOR))
        assertEquals(enviar, RegrasFaixaSincronizacao.acoes(1, EstadoSincronizacao.SINCRONIZADO))
        assertEquals(enviar, RegrasFaixaSincronizacao.acoes(1, null))
    }

    @Test
    fun `problema das configuracoes pede o responsavel e nao oferece enviar`() {
        EstadoSincronizacao.entries.filter { it.dependeDasConfiguracoes }.forEach {
            assertEquals(it.name, responsavel, RegrasFaixaSincronizacao.acoes(5, it))
        }
    }
}
