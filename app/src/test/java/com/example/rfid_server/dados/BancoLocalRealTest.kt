package com.example.rfid_server.dados

import android.content.Context
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import org.robolectric.Shadows.shadowOf
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Banco local num SQLite de verdade (Robolectric), sem aparelho:
 *  - migrações: cria o banco como o app antigo deixou (esquema exportado de cada versão), com dados dentro, e abre
 *    com o Room atual. O Room confere o esquema depois de migrar e recusa abrir se a migração estiver errada;
 *  - consultas: as regras escritas em SQL nos DAOs (os outros testes usam DAOs falsos em memória).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BancoLocalRealTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val nome = "banco-teste.db"
    private var banco: BancoLocal? = null

    @Before
    fun limpar() {
        context.deleteDatabase(nome)
    }

    @After
    fun fechar() {
        banco?.close()
        context.deleteDatabase(nome)
    }

    private fun abrirComRoom(): BancoLocal =
        Room.databaseBuilder(context, BancoLocal::class.java, nome)
            .addMigrations(
                BancoLocal.MIGRACAO_1_2, BancoLocal.MIGRACAO_2_3, BancoLocal.MIGRACAO_3_4,
                BancoLocal.MIGRACAO_4_5, BancoLocal.MIGRACAO_5_6, BancoLocal.MIGRACAO_6_7
            )
            .allowMainThreadQueries()
            .build()
            .also { banco = it }

    private fun emMemoria(): BancoLocal =
        Room.inMemoryDatabaseBuilder(context, BancoLocal::class.java).allowMainThreadQueries().build().also { banco = it }

    /** Cria o arquivo do banco exatamente como a [versao] do app deixava (SQL do esquema exportado) e roda [dados]. */
    private fun criarNaVersao(versao: Int, dados: (SupportSQLiteDatabase) -> Unit) {
        val esquema = JSONObject(File("schemas/com.example.rfid_server.dados.BancoLocal/$versao.json").readText()).getJSONObject("database")
        val sql = mutableListOf<String>()
        val entidades = esquema.getJSONArray("entities")
        for (i in 0 until entidades.length()) {
            val entidade = entidades.getJSONObject(i)
            val tabela = entidade.getString("tableName")
            sql += entidade.getString("createSql").replace("\${TABLE_NAME}", tabela)
            entidade.optJSONArray("indices")?.let { indices ->
                for (j in 0 until indices.length()) sql += indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", tabela)
            }
        }
        val setup = esquema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) sql += setup.getString(i)

        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(nome)
            .callback(object : SupportSQLiteOpenHelper.Callback(versao) {
                override fun onCreate(db: SupportSQLiteDatabase) = sql.forEach { db.execSQL(it) }
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build()
        FrameworkSQLiteOpenHelperFactory().create(config).use { helper -> dados(helper.writableDatabase) }
    }

    // ---------- Migrações ----------

    @Test
    fun `banco da versao 2 migra ate a atual sem perder leituras nem inventario`() {
        criarNaVersao(2) { db ->
            db.execSQL("INSERT INTO leituras (epc, lidoEm, situacao, envioConcluido, arquivada) VALUES ('E1', 1000, 'PENDENTE', 0, 0)")
            db.execSQL("INSERT INTO inventarios (id, local, iniciadoEm, aberturaEnviada, finalizacaoEnviada) VALUES ('inv-1', 'Sala 101', 500, 0, 0)")
            db.execSQL("INSERT INTO inventario_itens (inventarioId, epc, lidoEm, enviado) VALUES ('inv-1', 'E9', 600, 0)")
        }

        val banco = abrirComRoom()

        val leitura = banco.leituraDao().pendentes().single()
        assertEquals("E1", leitura.epc)
        assertNull("Leitura de antes do login de operador", leitura.sessaoOperador)
        assertFalse(leitura.recusada)

        val inventario = banco.inventarioDao().inventarioAgora("inv-1")!!
        assertEquals(0, inventario.recusas)
        val item = banco.inventarioDao().itensNaoEnviados("inv-1", 0, 10).single()
        assertEquals("E9", item.epc)
        assertEquals(0, item.recusas)
        assertNull(item.primeiraRecusaEm)
    }

    @Test
    fun `banco da versao 6 (app 1_6_0 nos coletores) migra para a 7 com as etiquetas pendentes`() {
        criarNaVersao(6) { db ->
            db.execSQL(
                "INSERT INTO inventarios (id, local, iniciadoEm, aberturaEnviada, finalizacaoEnviada, recusas) " +
                    "VALUES ('inv-1', 'Sala 101', 500, 1, 0, 2)"
            )
            db.execSQL("INSERT INTO inventario_itens (inventarioId, epc, lidoEm, enviado, sessaoOperador) VALUES ('inv-1', 'E1', 600, 0, 'sessao-ana')")
            db.execSQL("INSERT INTO inventario_itens (inventarioId, epc, lidoEm, enviado, sessaoOperador) VALUES ('inv-1', 'E2', 700, 1, 'sessao-ana')")
        }

        val dao = abrirComRoom().inventarioDao()

        assertEquals("Recusas do inventário mantidas", 2, dao.inventarioAgora("inv-1")!!.recusas)
        val pendente = dao.itensNaoEnviados("inv-1", 0, 10).single()
        assertEquals("E1", pendente.epc)
        assertEquals("sessao-ana", pendente.sessaoOperador)
        assertEquals(0, pendente.recusas)
    }

    // ---------- Consultas das leituras ----------

    @Test
    fun `leitura recusada que volta para a fila pelo cadastro deixa de contar como recusada e nao e apagada`() {
        val dao = emMemoria().leituraDao()
        val id = dao.inserir(LeituraEntity(epc = "E1", lidoEm = 1_000, arquivada = true))
        dao.marcarRecusada(id, "Recusada")

        assertEquals(1, dao.marcarCadastradaPorEpc("E1", "CI-1", null, null, null, "PAT-1", "SN-1"))

        val leitura = dao.pendentes().single()
        assertFalse(leitura.recusada)
        assertFalse(leitura.envioConcluido)
        assertEquals("Na fila: a limpeza de recusadas antigas não apaga", 0, dao.apagarRecusadasAntigas(Long.MAX_VALUE))
    }

    @Test
    fun `recusadas antigas fora da fila sao apagadas`() {
        val dao = emMemoria().leituraDao()
        val id = dao.inserir(LeituraEntity(epc = "E1", lidoEm = 1_000, arquivada = true))
        dao.marcarRecusada(id, "Recusada")

        assertEquals(0, dao.apagarRecusadasAntigas(1_000))
        assertEquals(1, dao.apagarRecusadasAntigas(1_001))
    }

    // ---------- Consultas do inventário ----------

    private fun inventarioComItens(dao: InventarioDao, vararg epcs: String): List<Long> {
        dao.inserirInventario(InventarioEntity("inv-1", "Sala 101", 1_000, aberturaEnviada = true))
        return epcs.map { dao.inserirItem(InventarioItemEntity(inventarioId = "inv-1", epc = it, lidoEm = 1_500)) }
    }

    @Test
    fun `etiquetas nao enviadas sao paginadas pelo id`() {
        val dao = emMemoria().inventarioDao()
        val ids = inventarioComItens(dao, "E1", "E2", "E3")

        assertEquals(listOf("E1", "E2"), dao.itensNaoEnviados("inv-1", 0, 2).map { it.epc })
        assertEquals(listOf("E3"), dao.itensNaoEnviados("inv-1", ids[1], 2).map { it.epc })
    }

    @Test
    fun `desistencia so depois do limite de recusas e do prazo`() {
        val dao = emMemoria().inventarioDao()
        val ids = inventarioComItens(dao, "E1")

        dao.registrarRecusaItens(ids, "Recusada", agora = 100)
        dao.registrarRecusaItens(ids, "Recusada", agora = 200)
        assertEquals("Só 2 recusas", 0, dao.desistirItens(ids, 3, antesDe = 1_000))

        dao.registrarRecusaItens(ids, "Recusada de novo", agora = 300)
        assertEquals("Primeira recusa depois do limite de data", 0, dao.desistirItens(ids, 3, antesDe = 99))
        assertEquals(1, dao.desistirItens(ids, 3, antesDe = 100))

        val item = dao.inventarioAgora("inv-1")!!.let { dao.itensNaoEnviados("inv-1", 0, 10) }
        assertTrue("Saiu da fila", item.isEmpty())
    }

    @Test
    fun `reabertura reenvia as aceitas, inclusive as que tinham sido recusadas antes, e nao as desistidas`() {
        val dao = emMemoria().inventarioDao()
        val (aceita, recusadaDepoisAceita, desistida) = inventarioComItens(dao, "ACEITA", "RECUSADA-E-ACEITA", "DESISTIDA")
        dao.definirAberturaEnviada("inv-1", true)

        dao.marcarItensEnviados(listOf(aceita))
        repeat(3) { dao.registrarRecusaItens(listOf(recusadaDepoisAceita, desistida), "Recusada", agora = 100) }
        dao.marcarItensEnviados(listOf(recusadaDepoisAceita))
        assertEquals(1, dao.desistirItens(listOf(desistida), 3, antesDe = 100))

        assertEquals(2, dao.prepararReabertura("inv-1"))

        val inventario = dao.inventarioAgora("inv-1")!!
        assertFalse(inventario.aberturaEnviada)
        assertEquals(setOf("ACEITA", "RECUSADA-E-ACEITA"), dao.itensNaoEnviados("inv-1", 0, 10).map { it.epc }.toSet())
    }

    @Test
    fun `etiqueta aceita tem as recusas apagadas`() {
        val dao = emMemoria().inventarioDao()
        val ids = inventarioComItens(dao, "E1")
        dao.registrarRecusaItens(ids, "Recusada", agora = 100)

        dao.marcarItensEnviados(ids)

        dao.prepararReabertura("inv-1")
        val item = dao.itensNaoEnviados("inv-1", 0, 10).single()
        assertEquals(0, item.recusas)
        assertNull(item.primeiraRecusaEm)
        assertNull(item.ultimoErro)
        assertNotNull(dao.inventarioAgora("inv-1"))
    }

    // ---------- Consultas das telas (LiveData) ----------

    /** Valor atual de um LiveData do Room: observa e deixa a consulta (em outra thread) e o looper principal rodarem. */
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

    @Test
    fun `aba de leitura mostra as que aguardam o servidor so da sessao atual`() {
        val dao = emMemoria().leituraDao()
        dao.inserir(LeituraEntity(epc = "ANTIGA", lidoEm = 1_000, arquivada = true))
        val cadastrada = dao.inserir(LeituraEntity(epc = "CAD", lidoEm = 1_000))
        dao.marcarCadastrada(cadastrada, "CI", null, null, null, "PAT", "SN")
        dao.inserir(LeituraEntity(epc = "P1", lidoEm = 1_000))
        dao.inserir(LeituraEntity(epc = "P2", lidoEm = 1_000))

        assertEquals("Mais nova primeiro, sem a arquivada nem a cadastrada", listOf("P2", "P1"), valor(dao.aguardandoDaSessao()).map { it.epc })
    }

    @Test
    fun `recusadas incluem as arquivadas e nao as que voltaram para a fila`() {
        val dao = emMemoria().leituraDao()
        val antiga = dao.inserir(LeituraEntity(epc = "ANTIGA", lidoEm = 1_000, arquivada = true))
        val atual = dao.inserir(LeituraEntity(epc = "ATUAL", lidoEm = 2_000))
        val devolvida = dao.inserir(LeituraEntity(epc = "DEVOLVIDA", lidoEm = 2_000))
        dao.inserir(LeituraEntity(epc = "NORMAL", lidoEm = 2_000))
        listOf(antiga, atual, devolvida).forEach { dao.marcarRecusada(it, "Sessão inválida") }

        dao.desfazerRecusas(listOf(devolvida))

        val recusadas = dao.recusadasAgora()
        assertEquals(listOf("ATUAL", "ANTIGA"), recusadas.map { it.epc })
        assertEquals("Sessão inválida", recusadas.first().ultimoErro)
        assertEquals("Devolvida volta a ser consultada", Situacao.PENDENTE, dao.pendentes().single { it.epc == "DEVOLVIDA" }.situacao)
    }

    @Test
    fun `avisos de recusa trazem inventarios recusados recentes e etiquetas desistidas`() {
        val dao = emMemoria().inventarioDao()
        // Recusado inteiro, recente
        dao.inserirInventario(InventarioEntity("recusado", "Sala 101", 1_000))
        dao.registrarRecusa("recusado", "Local inativo", agora = 2_000)
        dao.desistir("recusado", agora = 50_000)
        // Recusado inteiro, antes do período do aviso
        dao.inserirInventario(InventarioEntity("velho", "Sala 102", 1_000))
        dao.desistir("velho", agora = 5_000)
        // Inventário aceito, com 2 etiquetas desistidas, 1 recusada ainda na fila e 1 aceita
        dao.inserirInventario(InventarioEntity("parcial", "Almoxarifado", 1_000, aberturaEnviada = true))
        val ids = listOf("D1", "D2", "NA-FILA", "ACEITA").map { dao.inserirItem(InventarioItemEntity(inventarioId = "parcial", epc = it, lidoEm = 1_500)) }
        dao.registrarRecusaItens(ids.take(3), "Sessão do operador desativada", agora = 100)
        assertEquals(2, dao.desistirItens(ids.take(2), 1, antesDe = 100))
        dao.marcarItensEnviados(listOf(ids[3]))
        // Etiquetas desistidas de um inventário recusado inteiro: já entram no aviso dele
        dao.inserirItem(InventarioItemEntity(inventarioId = "recusado", epc = "X", lidoEm = 1_500, enviado = true, recusas = 1, primeiraRecusaEm = 100))

        val avisos = valor(dao.recusas(desde = 10_000))

        assertEquals(
            listOf(
                RecusaInventario("parcial", "Almoxarifado", "Sessão do operador desativada", 2),
                RecusaInventario("recusado", "Sala 101", "Local inativo", 0)
            ),
            avisos
        )
    }

    @Test
    fun `cancelar apaga o inventario e as etiquetas, e conta as ja enviadas`() {
        val dao = emMemoria().inventarioDao()
        val ids = inventarioComItens(dao, "E1", "E2")
        dao.inserirInventario(InventarioEntity("outro", "Sala 2", 1_000))
        dao.inserirItem(InventarioItemEntity(inventarioId = "outro", epc = "E1", lidoEm = 1_500))
        assertEquals(0, dao.contarItensEnviadosAgora("inv-1"))
        dao.marcarItensEnviados(listOf(ids[0]))
        assertEquals(1, dao.contarItensEnviadosAgora("inv-1"))

        dao.apagarInventario("inv-1")

        assertNull(dao.inventarioAgora("inv-1"))
        assertEquals(0, dao.contarItensAgora("inv-1"))
        assertEquals("O outro inventário fica", 1, dao.contarItensAgora("outro"))
    }
}
