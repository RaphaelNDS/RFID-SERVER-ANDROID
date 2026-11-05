package com.example.rfid_server.activities

import com.example.rfid_server.sincronizacao.EstadoSincronizacao
import org.junit.Assert.assertEquals
import org.junit.Test

class RegrasFaixaSincronizacaoTest {

    private val nenhuma = RegrasFaixaSincronizacao.Acoes(enviarAgora = false, chamarResponsavel = false)
    private val enviar = RegrasFaixaSincronizacao.Acoes(enviarAgora = true, chamarResponsavel = false)
    private val responsavel = RegrasFaixaSincronizacao.Acoes(enviarAgora = false, chamarResponsavel = true)

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
