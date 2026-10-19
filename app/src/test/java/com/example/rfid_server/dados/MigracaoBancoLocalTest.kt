package com.example.rfid_server.dados

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Confere as migrações do banco local contra o esquema que o próprio Room exporta no build (app/schemas).
 * Se alguém mudar uma entidade sem atualizar a migração, este teste falha aqui, e não no coletor
 * (onde o Room recusaria abrir o banco e o app fecharia).
 */
class MigracaoBancoLocalTest {

    private fun esquema(versao: Int): JSONObject {
        // Testes unitários rodam com o diretório do módulo "app" como diretório atual
        val arquivo = File("schemas/com.example.rfid_server.dados.BancoLocal/$versao.json")
        assertTrue("Esquema não encontrado em ${arquivo.absolutePath}: rode o build antes", arquivo.exists())
        return JSONObject(arquivo.readText()).getJSONObject("database")
    }

    /** CREATE TABLE e CREATE INDEX que o Room espera para a tabela, com o nome real no lugar do marcador. */
    private fun sqlEsperado(versao: Int, tabela: String): List<String> {
        val entidades = esquema(versao).getJSONArray("entities")
        val entidade = (0 until entidades.length()).map { entidades.getJSONObject(it) }
            .single { it.getString("tableName") == tabela }

        val sql = mutableListOf(entidade.getString("createSql"))
        entidade.optJSONArray("indices")?.let { indices ->
            (0 until indices.length()).forEach { sql += indices.getJSONObject(it).getString("createSql") }
        }
        return sql.map { it.replace("\${TABLE_NAME}", tabela) }
    }

    private fun tabelas(versao: Int): Set<String> {
        val entidades = esquema(versao).getJSONArray("entities")
        return (0 until entidades.length()).map { entidades.getJSONObject(it).getString("tableName") }.toSet()
    }

    @Test
    fun `esquemas exportados tem a versao do nome do arquivo`() {
        assertEquals(2, esquema(2).getInt("version"))
        assertEquals(3, esquema(3).getInt("version"))
    }

    @Test
    fun `migracao 1 para 2 cria as tabelas do inventario exatamente como o Room espera`() {
        val esperado = sqlEsperado(2, "inventarios") + sqlEsperado(2, "inventario_itens")

        assertEquals(esperado.sorted(), BancoLocal.SQL_VERSAO_2.sorted())
    }

    @Test
    fun `migracao 2 para 3 cria a tabela de cadastros exatamente como o Room espera`() {
        assertEquals(sqlEsperado(3, "cadastros").sorted(), BancoLocal.SQL_VERSAO_3.sorted())
    }

    @Test
    fun `cada migracao so cria tabelas novas e nao mexe nas anteriores`() {
        assertEquals(setOf("cadastros"), tabelas(3) - tabelas(2))
        assertTrue(BancoLocal.SQL_VERSAO_2.none { it.contains("`leituras`") })
        assertTrue(BancoLocal.SQL_VERSAO_3.none { it.contains("`leituras`") || it.contains("`inventario") })

        // As tabelas que já existiam na versão 2 continuam iguais na 3
        tabelas(2).forEach { assertEquals("Tabela $it mudou", sqlEsperado(2, it), sqlEsperado(3, it)) }
    }

    /** Colunas da tabela no esquema: nome → (tipo, obrigatória). */
    private fun colunas(versao: Int, tabela: String): Map<String, Pair<String, Boolean>> {
        val entidades = esquema(versao).getJSONArray("entities")
        val campos = (0 until entidades.length()).map { entidades.getJSONObject(it) }
            .single { it.getString("tableName") == tabela }.getJSONArray("fields")
        return (0 until campos.length()).map { campos.getJSONObject(it) }
            // O Room omite "notNull" quando a coluna é opcional
            .associate { it.getString("columnName") to (it.getString("affinity") to it.optBoolean("notNull", false)) }
    }

    @Test
    fun `migracao 3 para 4 acrescenta exatamente as colunas de sessao que o Room espera`() {
        assertEquals(4, esquema(4).getInt("version"))
        assertEquals("Nenhuma tabela nova na versão 4", tabelas(3), tabelas(4))

        // O que mudou de uma versão para a outra, tabela por tabela
        val acrescentadas = tabelas(3).flatMap { tabela ->
            (colunas(4, tabela) - colunas(3, tabela).keys).map { (coluna, tipo) -> Triple(tabela, coluna, tipo) }
        }.toSet()

        // Cada coluna nova precisa ser TEXT opcional (ALTER TABLE ... ADD COLUMN ... TEXT), igual à migração
        val esperado = BancoLocal.SQL_VERSAO_4.map { sql ->
            val (tabela, coluna) = Regex("ALTER TABLE `(\\w+)` ADD COLUMN `(\\w+)` TEXT").matchEntire(sql)!!.destructured
            Triple(tabela, coluna, "TEXT" to false)
        }.toSet()

        assertEquals(esperado, acrescentadas)

        // E nenhuma coluna existente mudou de tipo ou de obrigatoriedade
        tabelas(3).forEach { tabela ->
            colunas(3, tabela).forEach { (coluna, tipo) -> assertEquals("$tabela.$coluna mudou", tipo, colunas(4, tabela)[coluna]) }
        }
    }
}
