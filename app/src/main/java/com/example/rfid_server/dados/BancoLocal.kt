package com.example.rfid_server.dados

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Banco local do app (arquivo rfid-local.db no aparelho).
 * Ao mudar uma entidade, aumente a versão e escreva a migração: sem isso, leituras ainda
 * não enviadas seriam perdidas na atualização do app.
 * O esquema de cada versão fica em app/schemas (gerado no build) para conferir as migrações.
 */
@Database(
    entities = [LeituraEntity::class, InventarioEntity::class, InventarioItemEntity::class, CadastroEntity::class],
    version = 4,
    exportSchema = true
)
abstract class BancoLocal : RoomDatabase() {

    abstract fun leituraDao(): LeituraDao

    abstract fun inventarioDao(): InventarioDao

    abstract fun cadastroDao(): CadastroDao

    companion object {
        private const val TAG = "BANCO_LOCAL"

        /**
         * Versão 2: tabelas do inventário por local. Só cria tabelas novas; as leituras pendentes
         * da versão 1 continuam intactas. O SQL é o mesmo que o Room gera (BancoLocal_Impl).
         */
        internal val MIGRACAO_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Atualizando o banco local da versão 1 para a 2: tabelas de inventário")
                SQL_VERSAO_2.forEach { db.execSQL(it) }
            }
        }

        internal val SQL_VERSAO_2 = listOf(
            "CREATE TABLE IF NOT EXISTS `inventarios` (`id` TEXT NOT NULL, `local` TEXT NOT NULL, `iniciadoEm` INTEGER NOT NULL, `finalizadoEm` INTEGER, `aberturaEnviada` INTEGER NOT NULL, `finalizacaoEnviada` INTEGER NOT NULL, `ultimoErro` TEXT, PRIMARY KEY(`id`))",
            "CREATE TABLE IF NOT EXISTS `inventario_itens` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `inventarioId` TEXT NOT NULL, `epc` TEXT NOT NULL, `lidoEm` INTEGER NOT NULL, `enviado` INTEGER NOT NULL)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_inventario_itens_inventarioId_epc` ON `inventario_itens` (`inventarioId`, `epc`)",
            "CREATE INDEX IF NOT EXISTS `index_inventario_itens_enviado` ON `inventario_itens` (`enviado`)"
        )

        /** Versão 3: fila de cadastros de etiqueta feitos no coletor. Só cria a tabela nova. */
        internal val MIGRACAO_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Atualizando o banco local da versão 2 para a 3: tabela de cadastros de etiqueta")
                SQL_VERSAO_3.forEach { db.execSQL(it) }
            }
        }

        internal val SQL_VERSAO_3 = listOf(
            "CREATE TABLE IF NOT EXISTS `cadastros` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `epc` TEXT NOT NULL, `codigoInterno` TEXT NOT NULL, `patrimonio` TEXT NOT NULL, `numeroSerie` TEXT NOT NULL, `tipoId` INTEGER, `tipoNome` TEXT, `marcaId` INTEGER, `marcaNome` TEXT, `modeloId` INTEGER, `modeloNome` TEXT, `criadoEm` INTEGER NOT NULL, `situacao` TEXT NOT NULL, `erro` TEXT)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_cadastros_epc` ON `cadastros` (`epc`)"
        )

        /**
         * Versão 4: login de operador. Cada registro guarda a sessão de quem fez. Os registros que já
         * estavam na fila ficam com sessão nula e são aceitos pelo servidor por serem anteriores ao login.
         */
        internal val MIGRACAO_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                Log.i(TAG, "Atualizando o banco local da versão 3 para a 4: sessão do operador em cada registro")
                SQL_VERSAO_4.forEach { db.execSQL(it) }
            }
        }

        internal val SQL_VERSAO_4 = listOf(
            "ALTER TABLE `leituras` ADD COLUMN `sessaoOperador` TEXT",
            "ALTER TABLE `inventarios` ADD COLUMN `sessaoOperador` TEXT",
            "ALTER TABLE `inventarios` ADD COLUMN `sessaoFinalizacao` TEXT",
            "ALTER TABLE `inventario_itens` ADD COLUMN `sessaoOperador` TEXT",
            "ALTER TABLE `cadastros` ADD COLUMN `sessaoOperador` TEXT"
        )

        @Volatile
        private var instancia: BancoLocal? = null

        fun get(context: Context): BancoLocal =
            instancia ?: synchronized(this) {
                instancia ?: Room.databaseBuilder(context.applicationContext, BancoLocal::class.java, "rfid-local.db")
                    .addMigrations(MIGRACAO_1_2, MIGRACAO_2_3, MIGRACAO_3_4)
                    .build()
                    .also { instancia = it }
            }
    }
}
