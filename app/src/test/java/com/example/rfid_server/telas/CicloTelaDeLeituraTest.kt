package com.example.rfid_server.telas

import org.junit.Assert.assertEquals
import org.junit.Test

class CicloTelaDeLeituraTest {

    private val chamadas = mutableListOf<String>()
    private var sessaoValida = true
    private var temInventario = true

    private fun ciclo(
        lerAoRetomar: () -> Boolean = { true },
        comGanchos: Boolean = true
    ) = CicloTelaDeLeitura(
        exigirSessao = { chamadas += "exigirSessao"; sessaoValida },
        ligarLeitor = { chamadas += "definirOuvinte" },
        desligarLeitor = { chamadas += "removerOuvinte" },
        iniciarAvisoSessao = { chamadas += "avisoSessao.iniciar" },
        pararAvisoSessao = { chamadas += "avisoSessao.parar" },
        lerAoRetomar = lerAoRetomar,
        aoRetomar = if (comGanchos) ({ chamadas += "aoRetomar" }) else ({}),
        antesDePausar = if (comGanchos) ({ chamadas += "antesDePausar" }) else ({}),
        aoPausar = if (comGanchos) ({ chamadas += "aoPausar" }) else ({})
    )

    @Test
    fun `retomar liga o leitor antes do gancho da tela e so depois inicia o aviso de sessao`() {
        ciclo().retomar()

        assertEquals(listOf("exigirSessao", "definirOuvinte", "aoRetomar", "avisoSessao.iniciar"), chamadas)
    }

    @Test
    fun `sessao vencida ao retomar nao liga leitor, gancho nem aviso`() {
        sessaoValida = false

        ciclo().retomar()

        assertEquals(listOf("exigirSessao"), chamadas)
    }

    @Test
    fun `pausar encerra o que depende do leitor antes de solta-lo e para o aviso por ultimo`() {
        // Ex.: Localizar sai do modo localização enquanto ainda é dona das leituras
        ciclo().pausar()

        assertEquals(listOf("antesDePausar", "removerOuvinte", "aoPausar", "avisoSessao.parar"), chamadas)
    }

    @Test
    fun `pausar acontece mesmo com a sessao vencida`() {
        sessaoValida = false
        val c = ciclo(comGanchos = false)

        c.retomar()
        c.pausar()

        assertEquals(listOf("exigirSessao", "removerOuvinte", "avisoSessao.parar"), chamadas)
    }

    @Test
    fun `sem ganchos a ordem e a das telas simples`() {
        val c = ciclo(comGanchos = false)

        c.retomar()
        c.pausar()

        assertEquals(
            listOf("exigirSessao", "definirOuvinte", "avisoSessao.iniciar", "removerOuvinte", "avisoSessao.parar"),
            chamadas
        )
    }

    @Test
    fun `inventario sem inventario aberto nao liga o leitor ao retomar mas inicia o aviso`() {
        temInventario = false

        ciclo(lerAoRetomar = { temInventario }, comGanchos = false).retomar()

        assertEquals(listOf("exigirSessao", "avisoSessao.iniciar"), chamadas)
    }

    @Test
    fun `inventario aberto liga o leitor ao retomar`() {
        ciclo(lerAoRetomar = { temInventario }, comGanchos = false).retomar()

        assertEquals(listOf("exigirSessao", "definirOuvinte", "avisoSessao.iniciar"), chamadas)
    }

    @Test
    fun `condicao de leitura e avaliada a cada retomada`() {
        val c = ciclo(lerAoRetomar = { temInventario }, comGanchos = false)

        temInventario = false
        c.retomar()
        temInventario = true
        c.retomar()

        assertEquals(
            listOf("exigirSessao", "avisoSessao.iniciar", "exigirSessao", "definirOuvinte", "avisoSessao.iniciar"),
            chamadas
        )
    }
}
