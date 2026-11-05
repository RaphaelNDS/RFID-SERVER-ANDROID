package com.example.rfid_server.dados

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/** Referências de sessão ainda gravadas no banco (o CofreSessoes não pode apagar o token delas), num SQLite real. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessoesEmUsoTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val banco = Room.inMemoryDatabaseBuilder(context, BancoLocal::class.java).allowMainThreadQueries().build()

    @After
    fun fechar() = banco.close()

    @Test
    fun `leituras devolvem so as referencias do cofre, sem repetir`() {
        val dao = banco.leituraDao()
        dao.inserir(LeituraEntity(epc = "E1", lidoEm = 1, sessaoOperador = "sessao1:aaa"))
        dao.inserir(LeituraEntity(epc = "E2", lidoEm = 2, sessaoOperador = "sessao1:aaa"))
        dao.inserir(LeituraEntity(epc = "E3", lidoEm = 3, sessaoOperador = "token-puro-da-1.6.0"))
        dao.inserir(LeituraEntity(epc = "E4", lidoEm = 4))

        assertEquals(listOf("sessao1:aaa"), dao.sessoesEmUso())
    }

    @Test
    fun `inventarios juntam quem abriu, quem finalizou e quem leu cada etiqueta`() {
        val dao = banco.inventarioDao()
        dao.inserirInventario(InventarioEntity("inv-1", "Sala 101", 1, sessaoOperador = "sessao1:ana", sessaoFinalizacao = "sessao1:bruno"))
        dao.inserirItem(InventarioItemEntity(inventarioId = "inv-1", epc = "E1", lidoEm = 2, sessaoOperador = "sessao1:carla"))
        dao.inserirItem(InventarioItemEntity(inventarioId = "inv-1", epc = "E2", lidoEm = 3, sessaoOperador = "sessao1:ana"))

        assertEquals(setOf("sessao1:ana", "sessao1:bruno", "sessao1:carla"), dao.sessoesEmUso().toSet())
    }

    @Test
    fun `cancelar o inventario passa pela trava da sincronizacao`() {
        val dao = banco.inventarioDao()
        var travou = 0
        val repositorio = RepositorioInventario(
            dao = dao, executor = Executor { it.run() }, solicitarSincronizacao = {},
            sessaoAtual = { "sessao1:ana" }, semSincronizacao = { bloco -> travou++; bloco() }
        )
        repositorio.abrir("Sala 101")
        var resultado: RepositorioInventario.ResultadoCancelamento? = null

        repositorio.cancelar { resultado = it }

        assertEquals(1, travou)
        assertEquals(RepositorioInventario.ResultadoCancelamento.CANCELADO, resultado)
        assertEquals(null, dao.inventarioAbertoAgora())
    }
}
