package com.example.rfid_server.activities

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

class DiagnosticoTest {

    private val zona = ZoneId.of("America/Sao_Paulo")
    private val agora = OffsetDateTime.parse("2026-11-09T13:00:00-03:00").toInstant().toEpochMilli()

    private fun dados() = DadosDiagnostico(
        versaoApp = "2.1.0 (160)", android = "11", modelo = "MC3300x",
        servidor = "https://172.16.242.22:8443", certificado = "30:3F:97:AA:EA:F8:D2:EE:2B:9C:25:E2:77:92:10:5B",
        operador = "Ana", sessaoAte = agora + 3_600_000, leitor = "Leitor RFID conectado", bateria = 80,
        leiturasPendentes = 3, itensInventarioPendentes = 2, cadastrosPendentes = 1, justificativasPendentes = 4,
        errosGuardados = 0, leituraMaisAntiga = agora - 600_000, ultimaSincronizacaoOk = null,
        desvioRelogioMs = 5_000, espacoLivreMb = 2048, agora = agora
    )

    @Test
    fun `texto mostra fila somada, leitor, relogio e espaco`() {
        val texto = Diagnostico.texto(dados(), zona)

        assertTrue(texto, texto.contains("Aguardando envio: 10"))
        assertTrue(texto, texto.contains("leituras 3 · inventário 2"))
        assertTrue(texto, texto.contains("Leitor: Leitor RFID conectado · bateria 80%"))
        assertTrue(texto, texto.contains("Operador: Ana (até 09/11/2026 14:00)"))
        assertTrue(texto, texto.contains("Relógio: certo"))
        assertTrue(texto, texto.contains("Espaço livre: 2048 MB"))
        assertTrue(texto, texto.contains("Último envio completo: nenhum desde que o app abriu"))
    }

    @Test
    fun `sem servidor e sem operador diz isso em vez de vazio`() {
        val texto = Diagnostico.texto(dados().copy(servidor = null, certificado = null, operador = null, desvioRelogioMs = null), zona)

        assertTrue(texto, texto.contains("Servidor: não configurado"))
        assertTrue(texto, texto.contains("Certificado: não confirmado"))
        assertTrue(texto, texto.contains("Operador: ninguém logado"))
        assertTrue(texto, texto.contains("Relógio: ainda não comparado com o servidor"))
    }
}
