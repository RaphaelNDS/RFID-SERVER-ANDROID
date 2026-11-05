package com.example.rfid_server.sincronizacao

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.rfid_server.api.HttpClient
import com.example.rfid_server.dados.FakeLeituraDao
import com.example.rfid_server.service.TagService
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executor

/**
 * [Sincronizador.solicitarEmBreve]: com o gatilho apertado, cada etiqueta nova pedia uma passada e elas rodavam uma
 * atrás da outra. Agora os pedidos de ~2,5 s viram uma passada só. Agendador falso: os blocos só rodam quando o teste manda.
 */
class SolicitarEmBreveTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val agendados = mutableListOf<Pair<Long, () -> Unit>>()
    private var passadas = 0

    private val sincronizador = Sincronizador(
        dao = FakeLeituraDao(),
        tagService = TagService(HttpClient.criar { "chave-teste" }) { "http://127.0.0.1:1" },
        executor = Executor { it.run() },
        agendarNovaTentativa = {},
        // Uma vez por passada (fila vazia: termina sincronizada e manda os erros guardados)
        enviarErros = { passadas++ },
        agendar = { atraso, bloco -> agendados += atraso to bloco }
    )

    private fun rodarAgendados() {
        val agora = agendados.toList()
        agendados.clear()
        agora.forEach { (_, bloco) -> bloco() }
    }

    @Test
    fun `varios pedidos no intervalo viram uma passada so`() {
        repeat(30) { sincronizador.solicitarEmBreve() }

        assertEquals("Um agendamento só", 1, agendados.size)
        assertEquals(Sincronizador.ESPERA_PEDIDO_EM_BREVE_MS, agendados.single().first)
        assertEquals("Nada roda antes do prazo", 0, passadas)

        rodarAgendados()
        assertEquals(1, passadas)
    }

    @Test
    fun `pedido depois da passada agendada abre um intervalo novo`() {
        sincronizador.solicitarEmBreve()
        rodarAgendados()

        sincronizador.solicitarEmBreve()
        sincronizador.solicitarEmBreve()
        assertEquals(1, agendados.size)
        rodarAgendados()

        assertEquals(2, passadas)
    }

    @Test
    fun `solicitar continua imediato`() {
        sincronizador.solicitar()

        assertEquals(1, passadas)
        assertEquals(0, agendados.size)
    }

    @Test
    fun `agendador com defeito pede na hora e nao prende os pedidos seguintes`() {
        var falhar = true
        val comDefeito = Sincronizador(
            dao = FakeLeituraDao(),
            tagService = TagService(HttpClient.criar { "chave-teste" }) { "http://127.0.0.1:1" },
            executor = Executor { it.run() },
            agendarNovaTentativa = {},
            enviarErros = { passadas++ },
            agendar = { atraso, bloco -> if (falhar) throw IllegalStateException("desligado") else agendados += atraso to bloco }
        )

        comDefeito.solicitarEmBreve()
        assertEquals(1, passadas)

        falhar = false
        comDefeito.solicitarEmBreve()
        assertEquals(1, agendados.size)
    }
}
