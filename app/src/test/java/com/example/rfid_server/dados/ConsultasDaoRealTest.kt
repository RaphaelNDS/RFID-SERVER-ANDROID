package com.example.rfid_server.dados

import android.content.Context
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Consultas dos DAOs que só eram testadas pelos DAOs falsos (em memória, com as regras reescritas em Kotlin):
 * aqui rodam no SQLite de verdade (Robolectric + Room em memória), para pegar diferença entre o SQL e o falso.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsultasDaoRealTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val banco: BancoLocal =
        Room.inMemoryDatabaseBuilder(context, BancoLocal::class.java).allowMainThreadQueries().build()
    private val leituras = banco.leituraDao()
    private val inventarios = banco.inventarioDao()

    @After
    fun fechar() {
        banco.close()
    }

    private data class Linha(val situacao: String, val envioConcluido: Boolean, val recusada: Boolean, val arquivada: Boolean)

    /** Linha da leitura direto da tabela (o DAO não tem "todas"); null se foi apagada. */
    private fun linha(epc: String): Linha? =
        banco.query("SELECT situacao, envioConcluido, recusada, arquivada FROM leituras WHERE epc = ?", arrayOf(epc)).use { c ->
            if (!c.moveToFirst()) null
            else Linha(c.getString(0), c.getInt(1) == 1, c.getInt(2) == 1, c.getInt(3) == 1)
        }

    /** Valor atual de um LiveData do Room (mesma forma do BancoLocalRealTest). */
    private fun <T> valor(dados: LiveData<T>): T {
        val observador = Observer<T> { }
        dados.observeForever(observador)
        try {
            val limite = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < limite) {
                shadowOf(Looper.getMainLooper()).idle()
                dados.value?.let { return it }
                Thread.sleep(10)
            }
            throw AssertionError("LiveData sem valor depois de 5 s")
        } finally {
            dados.removeObserver(observador)
        }
    }

    // ================= LeituraDao =================

    @Test
    fun `apagarArquivadasConcluidas apaga so as arquivadas ja resolvidas e nao recusadas`() {
        val enviada = leituras.inserir(LeituraEntity(epc = "ENVIADA", lidoEm = 1, arquivada = true))
        leituras.marcarNaoCadastrada(enviada)
        leituras.marcarEnviada(enviada)
        leituras.inserir(LeituraEntity(epc = "PENDENTE", lidoEm = 1, arquivada = true))
        // Classificada, mas a leitura ainda não foi registrada no servidor (envioConcluido = 0)
        val semEnvio = leituras.inserir(LeituraEntity(epc = "SEM-ENVIO", lidoEm = 1, arquivada = true))
        leituras.marcarNaoCadastrada(semEnvio)
        val recusada = leituras.inserir(LeituraEntity(epc = "RECUSADA", lidoEm = 1, arquivada = true))
        leituras.marcarNaoCadastrada(recusada)
        leituras.marcarRecusada(recusada, "Recusada")
        val daSessao = leituras.inserir(LeituraEntity(epc = "DA-SESSAO", lidoEm = 1))
        leituras.marcarNaoCadastrada(daSessao)
        leituras.marcarEnviada(daSessao)

        assertEquals(1, leituras.apagarArquivadasConcluidas())

        assertNull(linha("ENVIADA"))
        listOf("PENDENTE", "SEM-ENVIO", "RECUSADA", "DA-SESSAO").forEach { assertNotNull("$it fica", linha(it)) }
    }

    @Test
    fun `arquivarSessao arquiva so as da sessao atual e devolve quantas`() {
        leituras.inserir(LeituraEntity(epc = "ANTIGA", lidoEm = 1, arquivada = true))
        leituras.inserir(LeituraEntity(epc = "E1", lidoEm = 1))
        leituras.inserir(LeituraEntity(epc = "E2", lidoEm = 1))

        assertEquals(2, leituras.arquivarSessao())
        assertEquals(emptyList<String>(), leituras.epcsDaSessao())
        assertEquals(0, leituras.arquivarSessao())
    }

    @Test
    fun `desfazerRecusas volta para a fila e so a que virou ERRO volta a PENDENTE`() {
        val semClassificar = leituras.inserir(LeituraEntity(epc = "SEM-CLASSE", lidoEm = 1))
        leituras.marcarRecusada(semClassificar, "Recusada")
        val cadastrada = leituras.inserir(LeituraEntity(epc = "CADASTRADA", lidoEm = 1))
        leituras.marcarCadastrada(cadastrada, "CI", null, null, null, "PAT", "SN")
        leituras.marcarRecusada(cadastrada, "Recusada")
        val foraDaLista = leituras.inserir(LeituraEntity(epc = "FORA", lidoEm = 1))
        leituras.marcarRecusada(foraDaLista, "Recusada")
        assertEquals("ERRO", linha("SEM-CLASSE")!!.situacao)

        leituras.desfazerRecusas(listOf(semClassificar, cadastrada))

        assertEquals(Linha("PENDENTE", envioConcluido = false, recusada = false, arquivada = false), linha("SEM-CLASSE"))
        assertEquals(Linha("CADASTRADA", envioConcluido = false, recusada = false, arquivada = false), linha("CADASTRADA"))
        assertEquals("Fora da lista não muda", Linha("ERRO", envioConcluido = true, recusada = true, arquivada = false), linha("FORA"))
    }

    @Test
    fun `contarSessao conta so a sessao atual e comeca em zero`() {
        assertEquals("Tabela vazia: COALESCE", ContagemSessao(), valor(leituras.contarSessao()))

        leituras.inserir(LeituraEntity(epc = "ANTIGA", lidoEm = 1, arquivada = true))
        val cadastrada = leituras.inserir(LeituraEntity(epc = "CAD", lidoEm = 1))
        leituras.marcarCadastrada(cadastrada, "CI", null, null, null, "PAT", "SN")
        val naoCadastrada = leituras.inserir(LeituraEntity(epc = "NAO", lidoEm = 1))
        leituras.marcarNaoCadastrada(naoCadastrada)
        leituras.inserir(LeituraEntity(epc = "AGUARDA", lidoEm = 1))
        val recusada = leituras.inserir(LeituraEntity(epc = "REC", lidoEm = 1))
        leituras.marcarRecusada(recusada, "Recusada")

        assertEquals(
            ContagemSessao(lidas = 4, cadastradas = 1, naoCadastradas = 1, aguardandoServidor = 1, recusadas = 1),
            valor(leituras.contarSessao())
        )
    }

    @Test
    fun `contagem da fila na hora e a leitura pendente mais antiga`() {
        assertEquals(0, leituras.contarPendentesAgora())
        assertNull(leituras.lidoEmPendenteMaisAntiga())

        leituras.inserir(LeituraEntity(epc = "NOVA", lidoEm = 5_000))
        leituras.inserir(LeituraEntity(epc = "ANTIGA", lidoEm = 2_000, arquivada = true))
        val enviada = leituras.inserir(LeituraEntity(epc = "ENVIADA", lidoEm = 1_000))
        leituras.marcarNaoCadastrada(enviada)
        leituras.marcarEnviada(enviada)

        assertEquals(2, leituras.contarPendentesAgora())
        assertEquals("A enviada (mais antiga ainda) não conta", 2_000L, leituras.lidoEmPendenteMaisAntiga())
    }

    // ================= InventarioDao =================

    private fun inventario(
        id: String, iniciadoEm: Long, aberturaEnviada: Boolean = true, finalizadoEm: Long? = null,
        finalizacaoEnviada: Boolean = false, recusadoEm: Long? = null
    ) = inventarios.inserirInventario(
        InventarioEntity(id, "Local $id", iniciadoEm, finalizadoEm = finalizadoEm, aberturaEnviada = aberturaEnviada,
            finalizacaoEnviada = finalizacaoEnviada, recusadoEm = recusadoEm)
    )

    private fun item(inventarioId: String, epc: String, enviado: Boolean = false, primeiraRecusaEm: Long? = null): Long =
        inventarios.inserirItem(
            InventarioItemEntity(inventarioId = inventarioId, epc = epc, lidoEm = 1, enviado = enviado, primeiraRecusaEm = primeiraRecusaEm)
        )

    @Test
    fun `comPendencias traz abertura, finalizacao ou etiqueta por enviar, do mais antigo ao mais novo, sem os recusados`() {
        inventario("ETIQUETA", iniciadoEm = 300, finalizadoEm = 900, finalizacaoEnviada = true)
        item("ETIQUETA", "E1", enviado = false)
        inventario("ABERTURA", iniciadoEm = 100, aberturaEnviada = false)
        inventario("FINALIZACAO", iniciadoEm = 200, finalizadoEm = 900, finalizacaoEnviada = false)
        inventario("PRONTO", iniciadoEm = 50, finalizadoEm = 900, finalizacaoEnviada = true)
        item("PRONTO", "E2", enviado = true)
        inventario("ABERTO-EM-DIA", iniciadoEm = 60)
        inventario("RECUSADO", iniciadoEm = 10, aberturaEnviada = false, recusadoEm = 500)

        assertEquals(listOf("ABERTURA", "FINALIZACAO", "ETIQUETA"), inventarios.comPendencias().map { it.id })
    }

    @Test
    fun `apagarConcluidos apaga so os finalizados inteiros no servidor, com as etiquetas`() {
        inventario("PRONTO", iniciadoEm = 1, finalizadoEm = 2, finalizacaoEnviada = true)
        item("PRONTO", "E1", enviado = true)
        inventario("FALTA-ETIQUETA", iniciadoEm = 1, finalizadoEm = 2, finalizacaoEnviada = true)
        item("FALTA-ETIQUETA", "E2", enviado = false)
        inventario("ABERTO", iniciadoEm = 1)
        inventario("FALTA-FINALIZACAO", iniciadoEm = 1, finalizadoEm = 2, finalizacaoEnviada = false)

        assertEquals(1, inventarios.apagarConcluidos())

        assertNull(inventarios.inventarioAgora("PRONTO"))
        assertEquals("Etiquetas apagadas junto", 0, inventarios.contarItensAgora("PRONTO"))
        listOf("FALTA-ETIQUETA", "ABERTO", "FALTA-FINALIZACAO").forEach { assertNotNull(it, inventarios.inventarioAgora(it)) }
        assertEquals(0, inventarios.apagarConcluidos())
    }

    @Test
    fun `apagarRecusadosAntigos apaga so os recusados antes do limite, com as etiquetas`() {
        inventario("VELHO", iniciadoEm = 1, recusadoEm = 500)
        item("VELHO", "E1")
        inventario("RECENTE", iniciadoEm = 1, recusadoEm = 1_000)
        inventario("NORMAL", iniciadoEm = 1)

        assertEquals(1, inventarios.apagarRecusadosAntigos(1_000))

        assertNull(inventarios.inventarioAgora("VELHO"))
        assertEquals(0, inventarios.contarItensAgora("VELHO"))
        assertNotNull(inventarios.inventarioAgora("RECENTE"))
        assertNotNull(inventarios.inventarioAgora("NORMAL"))
    }

    @Test
    fun `contarItensNaoEnviados ignora as etiquetas de inventarios recusados (NOT IN)`() {
        inventario("NORMAL", iniciadoEm = 1)
        item("NORMAL", "E1")
        item("NORMAL", "E2")
        item("NORMAL", "E3", enviado = true)
        inventario("RECUSADO", iniciadoEm = 1, recusadoEm = 10)
        item("RECUSADO", "E4")

        assertEquals(2, valor(inventarios.contarItensNaoEnviados()))
        assertEquals("A contagem na hora (cabeçalhos) segue a mesma regra", 2, inventarios.contarItensNaoEnviadosAgora())
        assertEquals("Por inventário conta mesmo o recusado", 1, valor(inventarios.contarItensNaoEnviados("RECUSADO")))
    }

    @Test
    fun `finalizar so vale uma vez e guarda quem finalizou`() {
        inventario("INV", iniciadoEm = 1)

        assertEquals(1, inventarios.finalizar("INV", 2_000, "sessao1:ana"))
        assertEquals("Já finalizado: não muda a hora nem a sessão", 0, inventarios.finalizar("INV", 3_000, "sessao1:bia"))

        val finalizado = inventarios.inventarioAgora("INV")!!
        assertEquals(2_000L, finalizado.finalizadoEm)
        assertEquals("sessao1:ana", finalizado.sessaoFinalizacao)
        assertNull("Nenhum aberto", inventarios.inventarioAbertoAgora())
    }

    @Test
    fun `reenviarItens volta as enviadas deste inventario e deixa as desistidas`() {
        inventario("INV", iniciadoEm = 1)
        item("INV", "ACEITA", enviado = true)
        item("INV", "DESISTIDA", enviado = true, primeiraRecusaEm = 100)
        item("INV", "NA-FILA", enviado = false)
        inventario("OUTRO", iniciadoEm = 1)
        item("OUTRO", "DE-OUTRO", enviado = true)

        assertEquals(1, inventarios.reenviarItens("INV"))

        assertEquals(setOf("ACEITA", "NA-FILA"), inventarios.itensNaoEnviados("INV", 0, 10).map { it.epc }.toSet())
        assertEquals("Outro inventário não muda", 1, inventarios.contarItensEnviadosAgora("OUTRO"))
    }

    // ================= CadastroDao =================

    @Test
    fun `cadastros pendentes contados na hora`() {
        val cadastros = banco.cadastroDao()
        cadastros.salvar(CadastroEntity(epc = "E1", codigoInterno = "CI-1", criadoEm = 1))
        val recusado = cadastros.salvar(CadastroEntity(epc = "E2", codigoInterno = "CI-2", criadoEm = 1))
        cadastros.marcarRecusado(recusado, "Recusado")

        assertEquals(1, cadastros.contarPendentesAgora())
    }
}
