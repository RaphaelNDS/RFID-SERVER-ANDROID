package com.example.rfid_server.dados

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.rfid_server.view.TagView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executor

class RepositorioLeiturasTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private var sincronizacoesPedidas = 0
    private var agora = 1_000L
    private var sessao: String? = "sessao-ana"

    private fun repositorio(dao: LeituraDao) = RepositorioLeituras(
        dao = dao,
        executor = Executor { it.run() },
        solicitarSincronizacao = { sincronizacoesPedidas++ },
        relogio = { agora },
        sessaoAtual = { sessao }
    )

    @Test
    fun `registrar guarda a leitura com a hora da leitura e pede sincronizacao`() {
        val dao = FakeLeituraDao()
        agora = 1_760_000_000_000L

        repositorio(dao).registrar("E1")

        val leitura = dao.porEpc("E1")
        assertEquals(Situacao.PENDENTE, leitura.situacao)
        assertEquals(1_760_000_000_000L, leitura.lidoEm)
        assertFalse(leitura.envioConcluido)
        assertEquals(1, sincronizacoesPedidas)
    }

    @Test
    fun `mesma tag lida varias vezes na sessao e guardada uma vez so`() {
        val dao = FakeLeituraDao()
        val repositorio = repositorio(dao)

        repeat(5) { repositorio.registrar("E1") }
        repositorio.registrar("E2")

        assertEquals(listOf("E1", "E2"), dao.leituras.map { it.epc })
        assertEquals(2, sincronizacoesPedidas)
    }

    @Test
    fun `tags ja lidas antes da recriacao da tela continuam valendo`() {
        // Ex.: processo do app recriado no meio da sessão: o que está no banco conta como lido
        val dao = FakeLeituraDao(LeituraEntity(epc = "E1", lidoEm = 0))

        repositorio(dao).registrar("E1")

        assertEquals(1, dao.leituras.size)
    }

    @Test
    fun `nova sessao limpa a tela e permite ler a mesma tag de novo`() {
        val dao = FakeLeituraDao()
        val repositorio = repositorio(dao)
        repositorio.registrar("E1")

        repositorio.iniciarNovaSessao("teste")
        repositorio.registrar("E1")

        assertEquals(2, dao.leituras.size)
        assertEquals(listOf(true, false), dao.leituras.map { it.arquivada })
    }

    @Test
    fun `nova sessao apaga o que ja foi enviado e mantem o que falta enviar`() {
        val dao = FakeLeituraDao(
            LeituraEntity(epc = "ENVIADA", lidoEm = 0, situacao = Situacao.CADASTRADA, envioConcluido = true),
            LeituraEntity(epc = "FALTA", lidoEm = 0)
        )

        repositorio(dao).iniciarNovaSessao("teste")

        assertEquals(listOf("FALTA"), dao.leituras.map { it.epc })
        assertTrue(dao.porEpc("FALTA").arquivada)
    }

    @Test
    fun `listas da tela mostram so a sessao atual separadas por situacao`() {
        val dao = FakeLeituraDao(
            LeituraEntity(epc = "C1", lidoEm = 0, situacao = Situacao.CADASTRADA, envioConcluido = true),
            LeituraEntity(epc = "N1", lidoEm = 0, situacao = Situacao.NAO_CADASTRADA, envioConcluido = true),
            LeituraEntity(epc = "P1", lidoEm = 0),
            LeituraEntity(epc = "VELHA", lidoEm = 0, situacao = Situacao.CADASTRADA, envioConcluido = true, arquivada = true)
        )
        val repositorio = repositorio(dao)

        assertEquals(listOf("C1"), repositorio.cadastradas.value!!.map { it.epc })
        assertEquals(listOf("N1"), repositorio.naoCadastradas.value!!.map { it.epc })
        assertEquals(1, repositorio.pendentes.value)
    }

    @Test
    fun `contadores mostram a sessao atual por situacao`() {
        val dao = FakeLeituraDao(
            LeituraEntity(epc = "C1", lidoEm = 0, situacao = Situacao.CADASTRADA, envioConcluido = true),
            LeituraEntity(epc = "C2", lidoEm = 0, situacao = Situacao.CADASTRADA),
            LeituraEntity(epc = "N1", lidoEm = 0, situacao = Situacao.NAO_CADASTRADA, envioConcluido = true),
            LeituraEntity(epc = "P1", lidoEm = 0),
            LeituraEntity(epc = "VELHA", lidoEm = 0, arquivada = true)
        )

        val contagem = repositorio(dao).contagemSessao.value

        assertEquals(ContagemSessao(lidas = 4, cadastradas = 2, naoCadastradas = 1, aguardandoServidor = 1), contagem)
    }

    @Test
    fun `contadores zeram na nova leitura e voltam a contar`() {
        val dao = FakeLeituraDao()
        val repositorio = repositorio(dao)
        repositorio.registrar("E1")
        repositorio.registrar("E2")
        assertEquals(2, repositorio.contagemSessao.value!!.lidas)

        repositorio.iniciarNovaSessao("teste")
        assertEquals(ContagemSessao(), repositorio.contagemSessao.value)

        repositorio.registrar("E1")
        assertEquals(1, repositorio.contagemSessao.value!!.lidas)
    }

    @Test
    fun `resumo dos contadores so mostra aguardando quando ha`() {
        assertEquals(
            "Lidas: 3  •  Cadastradas: 2  •  Não cadastradas: 1",
            ContagemSessao(lidas = 3, cadastradas = 2, naoCadastradas = 1).resumo()
        )
        assertEquals(
            "Lidas: 5  •  Cadastradas: 2  •  Não cadastradas: 1  •  Aguardando: 2",
            ContagemSessao(lidas = 5, cadastradas = 2, naoCadastradas = 1, aguardandoServidor = 2).resumo()
        )
    }

    @Test
    fun `paraTagView usa texto vazio quando faltam dados obrigatorios`() {
        val leitura = LeituraEntity(epc = "E1", lidoEm = 0, codigoInterno = null, patrimonio = null, numeroSerie = null)

        assertEquals(TagView("E1", "", null, null, null, "", ""), leitura.paraTagView())
    }
}
