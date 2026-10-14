package com.example.rfid_server.erros

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FilaErrosTest {

    private val arquivo = File.createTempFile("erros", ".json").apply { delete() }
    private var agora = 1_760_000_000_000L
    private var proximoId = 1

    private fun fila() = FilaErros(arquivo, "1.4.0", 15, { agora }, { "id-${proximoId++}" })

    @After
    fun apagar() {
        arquivo.delete()
    }

    @Test
    fun `erro guardado vai no formato do servidor com versao e operador`() {
        fila().registrar(TipoErro.FALHA_GERAL, "NullPointerException", "java.lang.NullPointerException\n\tat X.y(X.kt:1)", "Ana")

        val item = fila().pendentes().getJSONObject(0)
        assertEquals("id-1", item.getString("id"))
        assertEquals(TipoErro.FALHA_GERAL, item.getString("tipo"))
        assertEquals("1.4.0", item.getString("versaoApp"))
        assertEquals(15, item.getInt("codigoVersao"))
        assertEquals("Ana", item.getString("operador"))
        assertTrue(item.getString("detalhe").contains("X.kt:1"))
        assertFalse("Campos internos não vão para o servidor", item.has("chave") || item.has("ultimoMs"))
    }

    @Test
    fun `repetidos em sequencia viram um registro com o numero de vezes`() {
        val fila = fila()
        repeat(5) {
            fila.registrar(TipoErro.ERRO_SERVIDOR, "HTTP 500")
            agora += 60_000
        }

        val pendentes = fila.pendentes()
        assertEquals(1, pendentes.length())
        assertEquals(5, pendentes.getJSONObject(0).getInt("ocorrencias"))
    }

    @Test
    fun `mesmo erro depois de 10 minutos ou erro diferente vira outro registro`() {
        val fila = fila()
        fila.registrar(TipoErro.ERRO_SERVIDOR, "HTTP 500")
        fila.registrar(TipoErro.ERRO_SERVIDOR, "HTTP 503")
        agora += FilaErros.JANELA_AGRUPAMENTO_MS + 1
        fila.registrar(TipoErro.ERRO_SERVIDOR, "HTTP 503")

        assertEquals(3, fila.quantidade())
    }

    @Test
    fun `dados sensiveis sao mascarados antes de guardar`() {
        fila().registrar(TipoErro.FALHA_GERAL, "falha com senha=Kx9mPq2v", "X-Sessao-Operador: tokenSecreto\n\tat X.y")

        val texto = arquivo.readText()
        assertFalse(texto.contains("Kx9mPq2v"))
        assertFalse(texto.contains("tokenSecreto"))
        assertTrue(texto.contains("senha=***"))
    }

    @Test
    fun `envio leva no maximo 50 e remover tira so os confirmados`() {
        val fila = fila()
        repeat(60) { fila.registrar(TipoErro.ENVIO_RECUSADO, "recusa $it") }

        val lote = fila.pendentes()
        assertEquals(50, lote.length())
        fila.remover((0 until lote.length()).map { lote.getJSONObject(it).getString("id") })

        assertEquals(10, fila.quantidade())
    }

    @Test
    fun `guarda no maximo 200 e descarta os mais antigos`() {
        val fila = fila()
        repeat(210) { fila.registrar(TipoErro.ENVIO_RECUSADO, "recusa $it") }

        assertEquals(FilaErros.MAXIMO_GUARDADOS, fila.quantidade())
        assertEquals("recusa 10", fila.pendentes(1).getJSONObject(0).getString("mensagem"))
    }

    @Test
    fun `arquivo corrompido nao trava o app`() {
        arquivo.writeText("{isto não é json")

        fila().registrar(TipoErro.FALHA_GERAL, "depois do arquivo ruim")

        assertEquals(1, fila().quantidade())
    }
}
