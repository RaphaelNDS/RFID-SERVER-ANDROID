package com.example.rfid_server.activities

import com.example.rfid_server.dados.ComparacaoInventario
import com.example.rfid_server.dados.EsperadoLocal
import com.example.rfid_server.dados.ItemEsperado
import com.example.rfid_server.dados.LidaInventario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

class RegrasInventarioTest {

    private fun item(epc: String, patrimonio: String? = "P-$epc", tipo: String? = "Notebook", marca: String? = "Dell") =
        ItemEsperado(epc, patrimonio, null, tipo, marca, null)

    private val esperado = EsperadoLocal(
        local = "Sala 101",
        temReferencia = true,
        itens = listOf(item("E1"), item("E2"), item("E3", patrimonio = null))
    )
    private val patrimonios = esperado.itens.associate { it.epc to it.patrimonio }

    // ================= FILTRO =================

    @Test
    fun `campo de filtro so aparece com lista grande ou com texto digitado`() {
        assertFalse(FiltroInventario.mostrarCampo(19, ""))
        assertFalse(FiltroInventario.mostrarCampo(5, "   "))
        assertTrue(FiltroInventario.mostrarCampo(20, ""))
        assertTrue(FiltroInventario.mostrarCampo(3, "12"))
    }

    @Test
    fun `filtro do faltando procura no patrimonio e no epc sem diferenciar maiusculas`() {
        val itens = listOf(item("E200AA", "1234"), item("E200BB", "5678"), item("E300CC", null))

        assertEquals(listOf("E200AA"), FiltroInventario.faltando(itens, "23").map { it.epc })
        assertEquals(listOf("E200AA", "E200BB"), FiltroInventario.faltando(itens, "e200").map { it.epc })
        assertEquals(listOf("E300CC"), FiltroInventario.faltando(itens, " cc ").map { it.epc })
        assertEquals(itens, FiltroInventario.faltando(itens, ""))
        assertTrue(FiltroInventario.faltando(itens, "zzz").isEmpty())
    }

    @Test
    fun `filtro das lidas procura no patrimonio e no epc`() {
        val lidas = listOf(LidaInventario("X9", null, true), LidaInventario("E1", "P-E1", false))

        assertEquals(listOf("E1"), FiltroInventario.lidas(lidas, "p-e").map { it.epc })
        assertEquals(listOf("X9"), FiltroInventario.lidas(lidas, "x9").map { it.epc })
    }

    // ================= RITMO =================

    @Test
    fun `primeiro calculo sai na hora e os seguintes respeitam o intervalo`() {
        assertEquals(0, RitmoComparacao.espera(agora = 1_000, ultimoCalculo = null))
        assertEquals(200, RitmoComparacao.espera(agora = 1_100, ultimoCalculo = 1_000, intervaloMs = 300))
        assertEquals(0, RitmoComparacao.espera(agora = 1_300, ultimoCalculo = 1_000, intervaloMs = 300))
        assertEquals(0, RitmoComparacao.espera(agora = 5_000, ultimoCalculo = 1_000, intervaloMs = 300))
    }

    @Test
    fun `relogio que voltou nao faz esperar mais que o intervalo`() {
        assertEquals(300, RitmoComparacao.espera(agora = 0, ultimoCalculo = 10_000, intervaloMs = 300))
    }

    // ================= SITUAÇÃO DA TELA =================

    @Test
    fun `situacao compara e poe as fora da lista primeiro`() {
        val s = SituacaoTelaInventario.calcular(esperado, patrimonios, listOf("E1", "X9", "E3"), termo = "")

        assertEquals(3, s.totalLidas)
        assertEquals(2, s.comparacao!!.encontradas)
        assertEquals(1, s.comparacao!!.foraDaLista)
        assertEquals(listOf("E2"), s.faltando.map { it.epc })
        assertEquals(listOf("X9", "E1", "E3"), s.lidas.map { it.epc })
        assertTrue(s.lidas[0].foraDaLista)
        assertEquals("P-E1", s.lidas[1].patrimonio)
    }

    @Test
    fun `filtro nao muda as contagens so as listas`() {
        val s = SituacaoTelaInventario.calcular(esperado, patrimonios, listOf("E1", "X9"), termo = "x9")

        assertEquals(2, s.totalLidas)
        assertEquals(2, s.comparacao!!.faltando.size)
        assertEquals(listOf("X9"), s.lidas.map { it.epc })
        assertTrue(s.faltando.isEmpty())
    }

    @Test
    fun `sem lista do esperado nao ha comparacao nem fora da lista`() {
        val primeiro = EsperadoLocal(local = "Sala 101", temReferencia = false)

        for (e in listOf(null, primeiro)) {
            val s = SituacaoTelaInventario.calcular(e, emptyMap(), listOf("A", "B"), termo = "")
            assertNull(s.comparacao)
            assertTrue(s.faltando.isEmpty())
            assertEquals(listOf("A", "B"), s.lidas.map { it.epc })
            assertTrue(s.lidas.none { it.foraDaLista })
        }
    }

    // ================= RESUMO =================

    private val finalizadoEm = OffsetDateTime.parse("2026-11-05T14:30:00-03:00").toInstant().toEpochMilli()
    private val fuso = ZoneId.of("America/Sao_Paulo")

    @Test
    fun `texto compartilhado tem local data operador contagens e o que faltou`() {
        val c = ComparacaoInventario.comparar(
            esperado.copy(itens = listOf(item("E1"), item("E2", "777"), item("E3", patrimonio = null, tipo = null, marca = null))),
            listOf("E1", "X9")
        )
        val texto = ResumoFinalizacao("Sala 101", finalizadoEm, "Ana", 2, c).textoCompartilhar(fuso)

        assertEquals(
            """
            Inventário de Sala 101
            Finalizado em: 05/11/2026 14:30
            Operador: Ana
            Etiquetas lidas: 2
            Encontradas: 1 de 3 esperada(s)
            Faltando: 2
            Fora da lista: 1

            O que faltou:
            - Patrimônio 777 - Notebook · Dell
            - EPC E3
            """.trimIndent(),
            texto
        )
    }

    @Test
    fun `texto compartilhado mostra a justificativa de cada faltante`() {
        val c = ComparacaoInventario.comparar(
            esperado.copy(itens = listOf(item("E2", "777"), item("E3", patrimonio = null, tipo = null, marca = null))),
            emptyList()
        )
        val texto = ResumoFinalizacao("Sala 101", finalizadoEm, "Ana", 0, c)
            .textoCompartilhar(fuso, justificativas = mapOf("E2" to "Outro: levado para a obra"))

        assertTrue(texto, texto.endsWith("- Patrimônio 777 - Notebook · Dell (Outro: levado para a obra)\n- EPC E3"))
    }

    @Test
    fun `texto sem comparacao e sem operador`() {
        val texto = ResumoFinalizacao("Almoxarifado", finalizadoEm, null, 5, null).textoCompartilhar(fuso)

        assertTrue(texto.startsWith("Inventário de Almoxarifado\nFinalizado em: 05/11/2026 14:30\nOperador: não identificado\nEtiquetas lidas: 5\n"))
        assertTrue(texto.contains("Sem lista do esperado"))
        assertFalse(texto.contains("O que faltou"))
    }

    @Test
    fun `nada faltando nao lista faltantes`() {
        val c = ComparacaoInventario.comparar(esperado, listOf("E1", "E2", "E3"))
        val texto = ResumoFinalizacao("Sala 101", finalizadoEm, "Ana", 3, c).textoCompartilhar(fuso)

        assertTrue(texto.endsWith("Faltando: 0\nFora da lista: 0"))
    }

    @Test
    fun `faltante sem patrimonio e sem descricao aparece pelo epc`() {
        assertEquals("EPC E9", ResumoFinalizacao.descreverFaltante(item("E9", patrimonio = null, tipo = null, marca = null)))
        assertEquals("Patrimônio 1 - Notebook · Dell", ResumoFinalizacao.descreverFaltante(item("E9", patrimonio = "1")))
    }

    // ================= local do inventário =================

    @Test
    fun `local e normalizado como no servidor`() {
        assertEquals("Sala 101", LocalInventario.normalizar("  Sala   101 "))
        assertEquals("Sala 101 bloco B", LocalInventario.normalizar("Sala\t101\n bloco  B"))
        assertEquals("", LocalInventario.normalizar("   "))
    }

    @Test
    fun `local de ate 120 caracteres depois de normalizado e aceito`() {
        assertTrue(LocalInventario.dentroDoLimite("a".repeat(120)))
        assertFalse(LocalInventario.dentroDoLimite("a".repeat(121)))
        // Espaços repetidos e nas pontas não contam: viram um só, como no servidor
        assertTrue(LocalInventario.dentroDoLimite("  " + "a".repeat(60) + "     " + "b".repeat(59) + "  "))
        assertFalse(LocalInventario.dentroDoLimite("a".repeat(60) + " " + "b".repeat(60)))
    }

    // ================= CABEÇALHO =================

    @Test
    fun `instrucao do gatilho so aparece ate a primeira leitura`() {
        assertTrue(RegrasTelaInventario.mostrarInstrucao(0))
        assertFalse(RegrasTelaInventario.mostrarInstrucao(1))
        assertFalse(RegrasTelaInventario.mostrarInstrucao(500))
    }

    @Test
    fun `planilha tem uma linha por etiqueta com a situacao e a justificativa dos que faltaram`() {
        val itens = listOf(item("E1"), item("E2", patrimonio = "P;2"), item("E3"))
        val local = EsperadoLocal("Sala 101", temReferencia = true, itens = itens)
        val c = ComparacaoInventario.comparar(local, setOf("E1", "X9"))
        val resumo = ResumoFinalizacao("Sala 101", finalizadoEm, "Ana", 2, c, "inv-1", listOf("E1", "X9"), itens)

        val linhas = resumo.csv(mapOf("E2" to "Emprestado")).trim().lines()

        assertEquals("Situação;Patrimônio;Descrição;Etiqueta (EPC);Justificativa", linhas[0])
        assertEquals("Encontrada;P-E1;Notebook · Dell;E1;", linhas[1])
        assertEquals("Fora da lista;;;X9;", linhas[2])
        assertEquals("Ponto e vírgula no patrimônio vai entre aspas", "Faltando;\"P;2\";Notebook · Dell;E2;Emprestado", linhas[3])
        assertEquals("Faltando;P-E3;Notebook · Dell;E3;", linhas[4])
    }
}
