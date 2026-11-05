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
    fun `dois erros alternados num loop continuam agrupados cada um no seu registro`() {
        val fila = fila()
        repeat(50) {
            fila.registrar(TipoErro.FALHA_GERAL, "NullPointerException", "java.lang.NullPointerException\n\tat A.a(A.kt:1)")
            fila.registrar(TipoErro.FALHA_GERAL, "IllegalStateException", "java.lang.IllegalStateException\n\tat B.b(B.kt:2)")
            agora += 1_000
        }

        val pendentes = fila.pendentes()
        assertEquals(2, pendentes.length())
        assertEquals("NullPointerException", pendentes.getJSONObject(0).getString("mensagem"))
        assertEquals(50, pendentes.getJSONObject(0).getInt("ocorrencias"))
        assertEquals(50, pendentes.getJSONObject(1).getInt("ocorrencias"))
    }

    @Test
    fun `no corte a causa original fica e saem primeiro as repeticoes mais novas`() {
        val fila = fila()
        // A causa original, depois um loop longo (fora da janela) de dois erros que se repetem
        fila.registrar(TipoErro.FALHA_GERAL, "causa original", "java.lang.RuntimeException\n\tat C.c(C.kt:3)")
        repeat(FilaErros.MAXIMO_GUARDADOS + 50) {
            agora += FilaErros.JANELA_AGRUPAMENTO_MS + 1
            fila.registrar(TipoErro.ERRO_SERVIDOR, "HTTP 500 ${it % 2}")
        }

        assertEquals(FilaErros.MAXIMO_GUARDADOS, fila.quantidade())
        val todos = fila.pendentes(FilaErros.MAXIMO_GUARDADOS)
        assertEquals("A causa original não some", "causa original", todos.getJSONObject(0).getString("mensagem"))
        assertEquals("HTTP 500 0", todos.getJSONObject(1).getString("mensagem"))
        assertEquals("HTTP 500 1", todos.getJSONObject(2).getString("mensagem"))
        // Nenhuma ocorrência perdida: as das repetições descartadas somam na primeira de cada uma
        val total = (0 until todos.length()).sumOf { todos.getJSONObject(it).getInt("ocorrencias") }
        assertEquals(1 + FilaErros.MAXIMO_GUARDADOS + 50, total)
    }

    @Test
    fun `fila grande demais perde primeiro o stack dos mais antigos`() {
        val fila = fila()
        val stackGrande = "java.lang.RuntimeException\n" + "\tat X.y(X.kt:1)\n".repeat(480) // ~7,7 KB (o limite é 8000)
        repeat(100) {
            fila.registrar(TipoErro.FALHA_GERAL, "falha $it", stackGrande.replace("X.y", "X$it.y"))
        }

        assertTrue("Arquivo dentro do limite: ${arquivo.length()}", arquivo.length() <= FilaErros.MAXIMO_BYTES)
        assertEquals("Nenhum registro descartado: só os detalhes", 100, fila.quantidade())
        val todos = fila.pendentes(100)
        assertTrue("O mais antigo perdeu o stack", todos.getJSONObject(0).isNull("detalhe"))
        assertFalse("O mais novo mantém o stack", todos.getJSONObject(99).isNull("detalhe"))
    }

    @Test
    fun `envio que falhou nao e repetido logo em seguida se nada mudou`() {
        val fila = fila()
        fila.registrar(TipoErro.ENVIO_RECUSADO, "recusa")
        var envios = 0
        val falha = { _: org.json.JSONArray ->
            envios++
            com.example.rfid_server.service.RespostaServidor.Falha(com.example.rfid_server.service.MotivoFalha.ERRO_SERVIDOR, "HTTP 503")
        }

        RegistroErros.enviarPendentes(fila, falha)
        RegistroErros.enviarPendentes(fila, falha)
        assertEquals("Mesma passada, mesma fila: não repete", 1, envios)

        fila.registrar(TipoErro.FALHA_GERAL, "erro novo")
        RegistroErros.enviarPendentes(fila, falha)
        assertEquals("Erro novo na fila: tenta de novo", 2, envios)

        agora += FilaErros.JANELA_REENVIO_MS
        RegistroErros.enviarPendentes(fila, falha)
        assertEquals("Passou o tempo: tenta de novo", 3, envios)
    }

    @Test
    fun `arquivo corrompido nao trava o app`() {
        arquivo.writeText("{isto não é json")

        fila().registrar(TipoErro.FALHA_GERAL, "depois do arquivo ruim")

        assertEquals(1, fila().quantidade())
    }

    @Test
    fun `erro repetido durante o envio nao perde as ocorrencias novas`() {
        val fila = fila()
        repeat(3) { fila.registrar(TipoErro.ERRO_SERVIDOR, "HTTP 500") }
        fila.registrar(TipoErro.ENVIO_RECUSADO, "recusa")
        val lote = fila.pendentes()

        // Enquanto o lote vai ao servidor, o mesmo erro acontece mais 2 vezes (agrupado no mesmo registro)
        agora += 60_000
        repeat(2) { fila.registrar(TipoErro.ENVIO_RECUSADO, "recusa") }

        assertEquals(1, fila.confirmarEnvio(lote))

        val restante = fila.pendentes()
        assertEquals(1, restante.length())
        with(restante.getJSONObject(0)) {
            assertEquals("Só as que não foram no lote", 2, getInt("ocorrencias"))
            assertEquals("recusa", getString("mensagem"))
            assertFalse("Outro id: não é o mesmo envio", getString("id") in listOf("id-1", "id-2"))
        }

        // Continua agrupando as próximas repetições no mesmo registro
        fila.registrar(TipoErro.ENVIO_RECUSADO, "recusa")
        assertEquals(3, fila.pendentes().getJSONObject(0).getInt("ocorrencias"))
    }

    @Test
    fun `lote recusado de vez pelo servidor e descartado e nao trava a fila`() {
        val fila = fila()
        repeat(60) { fila.registrar(TipoErro.ENVIO_RECUSADO, "recusa $it") }
        val enviados = mutableListOf<Int>()

        RegistroErros.enviarPendentes(fila) { lote ->
            enviados += lote.length()
            com.example.rfid_server.service.RespostaServidor.Falha(
                com.example.rfid_server.service.MotivoFalha.REQUISICAO_RECUSADA, "Servidor recusou a requisição (HTTP 400)"
            )
        }
        assertEquals("Os 50 recusados saem", 10, fila.quantidade())

        RegistroErros.enviarPendentes(fila) { lote ->
            enviados += lote.length()
            com.example.rfid_server.service.RespostaServidor.Sucesso(Unit)
        }
        assertEquals(listOf(50, 10), enviados)
        assertEquals(0, fila.quantidade())
    }

    @Test
    fun `falha passageira ao enviar os erros mantem tudo na fila`() {
        val fila = fila()
        repeat(3) { fila.registrar(TipoErro.ENVIO_RECUSADO, "recusa $it") }

        RegistroErros.enviarPendentes(fila) {
            com.example.rfid_server.service.RespostaServidor.Falha(com.example.rfid_server.service.MotivoFalha.ERRO_SERVIDOR, "HTTP 503")
        }

        assertEquals(3, fila.quantidade())
    }
}
