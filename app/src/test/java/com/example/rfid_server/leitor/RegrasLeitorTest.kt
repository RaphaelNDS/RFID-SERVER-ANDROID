package com.example.rfid_server.leitor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RegrasLeitorTest {

    // ---------- Tradução dos motivos ----------

    @Test
    fun `causa reconhecida pelo codigo do SDK vence as palavras do texto`() {
        val m = MotivoLeitor.classificar(CausaFalhaLeitor.OPERACAO_EM_ANDAMENTO, listOf("battery low"))
        assertEquals(CausaFalhaLeitor.OPERACAO_EM_ANDAMENTO, m.causa)
        assertEquals("battery low", m.detalhe)
    }

    @Test
    fun `sem codigo do SDK procura palavras chave no texto`() {
        fun causa(texto: String) = MotivoLeitor.classificar(null, listOf(null, texto)).causa
        assertEquals(CausaFalhaLeitor.BATERIA, causa("Operation failed: Battery level too low"))
        assertEquals(CausaFalhaLeitor.TEMPERATURA, causa("Reader TEMPERATURE too high"))
        assertEquals(CausaFalhaLeitor.OCUPADO_OUTRO_APP, causa("Reader is in use by another application"))
        assertEquals(CausaFalhaLeitor.OCUPADO_OUTRO_APP, causa("Device busy"))
        assertEquals("Visto no MC3300x com outro app segurando o leitor", CausaFalhaLeitor.OCUPADO_OUTRO_APP, causa("RFID_COMM_OPEN_ERROR"))
        assertEquals("No berço, mesmo com o código de operação em andamento", CausaFalhaLeitor.CARREGANDO,
            MotivoLeitor.classificar(CausaFalhaLeitor.OPERACAO_EM_ANDAMENTO, listOf("Charging in Progress-Command Not allowed")).causa)
        assertEquals(CausaFalhaLeitor.OPERACAO_EM_ANDAMENTO, causa("RFID_OPERATION_IN_PROGRESS"))
        assertEquals(CausaFalhaLeitor.REGIAO_NAO_CONFIGURADA, causa("Region not configured"))
        assertEquals(CausaFalhaLeitor.TEMPO_ESGOTADO, causa("Command timed out"))
        assertEquals(CausaFalhaLeitor.SEM_COMUNICACAO, causa("RFID_COMM_NO_CONNECTION"))
        assertEquals(CausaFalhaLeitor.DESCONHECIDA, causa("Erro qualquer 0x42"))
    }

    @Test
    fun `codigo desconhecido do SDK nao impede a busca no texto`() {
        val m = MotivoLeitor.classificar(CausaFalhaLeitor.DESCONHECIDA, listOf("low battery"))
        assertEquals(CausaFalhaLeitor.BATERIA, m.causa)
    }

    @Test
    fun `detalhe e o primeiro texto nao vazio, numa linha so e com tamanho limitado`() {
        assertEquals("falhou de vez", MotivoLeitor.detalhe(listOf(null, "  ", " falhou \n de   vez ")))
        assertEquals("", MotivoLeitor.detalhe(listOf(null, "")))
        assertEquals(120, MotivoLeitor.detalhe(listOf("x".repeat(500))).length)
    }

    @Test
    fun `toda causa conhecida tem texto proprio e a desconhecida usa o do SDK`() {
        val conhecidas = CausaFalhaLeitor.entries.filter { it != CausaFalhaLeitor.DESCONHECIDA }
        val recursos = conhecidas.map { assertNotNull(it.name, MotivoLeitor.recurso(it)); MotivoLeitor.recurso(it) }
        assertEquals(conhecidas.size, recursos.toSet().size)
        assertNull(MotivoLeitor.recurso(CausaFalhaLeitor.DESCONHECIDA))
    }

    @Test
    fun `minuscula inicial so em palavra comum`() {
        assertEquals("nenhum leitor encontrado", MotivoLeitor.minusculaInicial("Nenhum leitor encontrado"))
        assertEquals("RFID_COMM_NO_CONNECTION", MotivoLeitor.minusculaInicial("RFID_COMM_NO_CONNECTION"))
        assertEquals("SDK falhou", MotivoLeitor.minusculaInicial("SDK falhou"))
        assertEquals("", MotivoLeitor.minusculaInicial(""))
    }

    // ---------- Bateria ----------

    @Test
    fun `limiares de bateria`() {
        assertEquals(AlertaBateria.NORMAL, BateriaLeitor.classificar(15, false))
        assertEquals(AlertaBateria.BAIXA, BateriaLeitor.classificar(14, false))
        assertEquals(AlertaBateria.BAIXA, BateriaLeitor.classificar(5, false))
        assertEquals(AlertaBateria.CRITICA, BateriaLeitor.classificar(4, false))
        assertEquals(AlertaBateria.CRITICA, BateriaLeitor.classificar(0, false))
    }

    @Test
    fun `sem leitura ou carregando nao avisa`() {
        assertEquals(AlertaBateria.NORMAL, BateriaLeitor.classificar(null, false))
        assertEquals(AlertaBateria.NORMAL, BateriaLeitor.classificar(-1, false))
        assertEquals(AlertaBateria.NORMAL, BateriaLeitor.classificar(3, true))
    }

    @Test
    fun `bip de bateria so ao entrar na faixa critica`() {
        assertTrue(BateriaLeitor.deveTocarAlerta(AlertaBateria.BAIXA, AlertaBateria.CRITICA))
        assertTrue(BateriaLeitor.deveTocarAlerta(AlertaBateria.NORMAL, AlertaBateria.CRITICA))
        assertFalse(BateriaLeitor.deveTocarAlerta(AlertaBateria.CRITICA, AlertaBateria.CRITICA))
        assertFalse(BateriaLeitor.deveTocarAlerta(AlertaBateria.NORMAL, AlertaBateria.BAIXA))
    }

    @Test
    fun `percentual da bateria do aparelho`() {
        assertEquals(12, BateriaLeitor.percentual(12, 100))
        assertEquals(50, BateriaLeitor.percentual(128, 256))
        assertNull(BateriaLeitor.percentual(-1, 100))
        assertNull(BateriaLeitor.percentual(50, 0))
        assertEquals(100, BateriaLeitor.percentual(300, 100))
    }

    // ---------- Faixa ----------

    private val motivo = MotivoFalhaLeitor(CausaFalhaLeitor.BATERIA, "battery low")

    @Test
    fun `desconectado mostra o motivo quando ha`() {
        assertEquals(MensagemFaixa.DESCONECTADO, FaixaLeitor.escolher(EstadoLeitor.DESCONECTADO, DetalheLeitor(), 0))
        assertEquals(
            MensagemFaixa.DESCONECTADO_MOTIVO,
            FaixaLeitor.escolher(EstadoLeitor.DESCONECTADO, DetalheLeitor(motivoDesconexao = motivo), 0)
        )
        assertEquals(MensagemFaixa.PARADO_MOTIVO, FaixaLeitor.escolher(EstadoLeitor.PARADO, DetalheLeitor(motivoDesconexao = motivo), 0))
        assertEquals(MensagemFaixa.PARADO, FaixaLeitor.escolher(EstadoLeitor.PARADO, DetalheLeitor(), 0))
    }

    @Test
    fun `falha de leitura aparece por alguns segundos e depois sai`() {
        val detalhe = DetalheLeitor(falhaLeitura = motivo, momentoFalhaLeitura = 10_000)
        assertEquals(MensagemFaixa.FALHA_LEITURA, FaixaLeitor.escolher(EstadoLeitor.CONECTADO, detalhe, 10_000))
        assertEquals(FaixaLeitor.DURACAO_FALHA_LEITURA_MS - 1_000, FaixaLeitor.redesenharEmMs(detalhe, 11_000))

        val depois = 10_000 + FaixaLeitor.DURACAO_FALHA_LEITURA_MS
        assertEquals(MensagemFaixa.CONECTADO, FaixaLeitor.escolher(EstadoLeitor.CONECTADO, detalhe, depois))
        assertNull(FaixaLeitor.redesenharEmMs(detalhe, depois))
    }

    @Test
    fun `conectado o mais grave vence`() {
        val config = FalhaConfiguracao(ParteConfiguracao.GATILHO, motivo)
        val tudo = DetalheLeitor(falhaConfiguracao = config, falhaLeitura = motivo, momentoFalhaLeitura = 0, bateria = 3)
        assertEquals(MensagemFaixa.FALHA_LEITURA, FaixaLeitor.escolher(EstadoLeitor.CONECTADO, tudo, 100))
        assertEquals(MensagemFaixa.BATERIA_CRITICA, FaixaLeitor.escolher(EstadoLeitor.CONECTADO, tudo.copy(falhaLeitura = null), 100))
        assertEquals(
            MensagemFaixa.FALHA_CONFIGURACAO,
            FaixaLeitor.escolher(EstadoLeitor.CONECTADO, tudo.copy(falhaLeitura = null, bateria = 12), 100)
        )
        assertEquals(MensagemFaixa.BATERIA_BAIXA, FaixaLeitor.escolher(EstadoLeitor.CONECTADO, DetalheLeitor(bateria = 12), 100))
        assertEquals(MensagemFaixa.CONECTADO, FaixaLeitor.escolher(EstadoLeitor.CONECTADO, DetalheLeitor(bateria = 80), 100))
    }

    @Test
    fun `avisos de conectado nao aparecem desconectado`() {
        val detalhe = DetalheLeitor(falhaLeitura = motivo, momentoFalhaLeitura = 0, bateria = 2)
        assertEquals(MensagemFaixa.CONECTANDO, FaixaLeitor.escolher(EstadoLeitor.CONECTANDO, detalhe, 100))
        assertEquals(MensagemFaixa.DESCONECTADO, FaixaLeitor.escolher(EstadoLeitor.DESCONECTADO, detalhe, 100))
    }

    @Test
    fun `cores da faixa`() {
        assertEquals(CorFaixa.SUCESSO, FaixaLeitor.cor(MensagemFaixa.CONECTADO))
        assertEquals(CorFaixa.ATENCAO, FaixaLeitor.cor(MensagemFaixa.BATERIA_BAIXA))
        assertEquals(CorFaixa.ATENCAO, FaixaLeitor.cor(MensagemFaixa.FALHA_CONFIGURACAO))
        assertEquals(CorFaixa.ERRO, FaixaLeitor.cor(MensagemFaixa.BATERIA_CRITICA))
        assertEquals(CorFaixa.ERRO, FaixaLeitor.cor(MensagemFaixa.FALHA_LEITURA))
    }

    // ---------- Agrupamento dos registros ----------

    private var agora = 0L
    private val agrupador = AgrupadorFalhas(intervaloMs = 60_000, relogio = { agora })

    @Test
    fun `no maximo um registro por minuto e o seguinte conta os omitidos`() {
        assertEquals(0, agrupador.liberar())
        agora = 10_000
        assertNull(agrupador.liberar())
        agora = 59_999
        assertNull(agrupador.liberar())
        agora = 60_000
        assertEquals(2, agrupador.liberar())
        agora = 200_000
        assertEquals(0, agrupador.liberar())
    }

    @Test
    fun `sufixo dos omitidos`() {
        assertEquals("", AgrupadorFalhas.sufixo(0))
        assertEquals(" (+3 ocorrência(s) desde o registro anterior)", AgrupadorFalhas.sufixo(3))
    }

    // ---------- Volta depois de uma queda ----------

    @Test
    fun `mensagem da volta do leitor`() {
        assertEquals("Leitor RFID voltou depois de menos de 1 min e 1 tentativa", ReconexaoLeitor.mensagemVolta(5_000, 1))
        assertEquals("Leitor RFID voltou depois de 3 min e 7 tentativas", ReconexaoLeitor.mensagemVolta(3 * 60_000 + 59_000, 7))
        assertEquals("Leitor RFID voltou depois de menos de 1 min e 2 tentativas", ReconexaoLeitor.mensagemVolta(-10, 2))
    }

    @Test
    fun `coletor no carregador nao e defeito para a tela de erros`() {
        assertEquals(false, MotivoLeitor.ehDefeito(CausaFalhaLeitor.CARREGANDO))
        assertEquals(true, MotivoLeitor.ehDefeito(CausaFalhaLeitor.SEM_COMUNICACAO))
        assertEquals(true, MotivoLeitor.ehDefeito(CausaFalhaLeitor.DESCONHECIDA))
    }
}
