package com.example.rfid_server.sincronizacao

import com.example.rfid_server.SessaoOperador
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Correções da revisão da sincronização e da sessão (versão 2.7.3). */
class RevisaoSincronizacao273Test {

    @get:Rule
    val pasta = TemporaryFolder()

    @Test
    fun `justificativa trocada durante o envio nao e apagada com a enviada`() {
        val fila = FilaJustificativas(File(pasta.root, "justificativas.json"))
        fila.adicionar("inv-1", listOf("E1"), FilaJustificativas.Motivo.EMPRESTADO, null, 1_000)
        val enviadas = fila.pendentes()

        // O operador troca o motivo enquanto o envio está no ar
        fila.adicionar("inv-1", listOf("E1"), FilaJustificativas.Motivo.MANUTENCAO, null, 2_000)
        fila.removerEnviados(enviadas)

        assertEquals(FilaJustificativas.Motivo.MANUTENCAO, fila.motivos("inv-1")["E1"])
        assertEquals(1, fila.pendentes().size)

        fila.removerEnviados(fila.pendentes())
        assertEquals(0, fila.pendentes().size)
    }

    private fun sessao(expiraEm: Long, entrouEm: Long?, entrouDecorrido: Long?) = SessaoOperador(
        token = "t", expiraEm = expiraEm, nome = "N", email = "e", podeLer = true, podeCadastrar = false,
        entrouEm = entrouEm, entrouDecorrido = entrouDecorrido
    )

    @Test
    fun `fim da sessao considera o tempo desde o login com o relogio atrasado`() {
        // Entrou às 1000 (relógio) com 100 (desde a ligação); sessão de 10_000
        val s = sessao(expiraEm = 11_000, entrouEm = 1_000, entrouDecorrido = 100)
        // Relógio certo: termina no expiraEm
        assertEquals(11_000, s.fimEm(agora = 5_000, agoraDecorrido = 4_100))
        // Relógio atrasado 3_000 depois de 4_000 de uso: o relógio diz 2_000, mas faltam só 6_000 de verdade
        assertEquals(8_000, s.fimEm(agora = 2_000, agoraDecorrido = 4_100))
        // Sem o tempo do login (sessão de versão antiga) ou depois de reiniciar o aparelho: só o relógio
        assertEquals(11_000, sessao(11_000, null, null).fimEm(2_000, 4_100))
        assertEquals(11_000, s.fimEm(agora = 2_000, agoraDecorrido = 50))
    }
}
