package com.example.rfid_server.activities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConferenciaApkTest {

    private val pacote = "br.com.bkpbr.rfidandroid"
    private val chave = setOf(ConferenciaApk.sha256Hex("certificado de produção".toByteArray()))
    private val outraChave = setOf(ConferenciaApk.sha256Hex("certificado de outra pessoa".toByteArray()))

    private fun problema(
        pacoteApk: String? = pacote,
        certificadosApk: Set<String> = chave,
        codigoApk: Long? = 48,
        certificadosInstalado: Set<String> = chave,
        codigoAnunciado: Long = 48
    ) = ConferenciaApk.problema(pacote, certificadosInstalado, codigoAnunciado, pacoteApk, certificadosApk, codigoApk)

    @Test
    fun `APK do mesmo app, mesma chave e codigo anunciado e aceito`() {
        assertNull(problema())
    }

    @Test
    fun `APK de outro pacote e recusado`() {
        val motivo = problema(pacoteApk = "com.exemplo.malicioso")
        assertNotNull(motivo)
        assertTrue(motivo!!.contains("outro app"))
    }

    @Test
    fun `APK assinado com outra chave e recusado`() {
        assertTrue(problema(certificadosApk = outraChave)!!.contains("outra chave"))
        // Uma chave a mais (mais de um assinante) também não serve
        assertTrue(problema(certificadosApk = chave + outraChave)!!.contains("outra chave"))
    }

    @Test
    fun `APK sem assinatura ou ilegivel e recusado`() {
        assertEquals("o APK não tem assinatura legível", problema(certificadosApk = emptySet()))
        assertEquals("o arquivo baixado não é um APK válido", problema(pacoteApk = null, codigoApk = null))
        assertEquals("não foi possível ler a assinatura do app instalado", problema(certificadosInstalado = emptySet()))
    }

    @Test
    fun `codigo de versao diferente do anunciado e recusado`() {
        assertTrue(problema(codigoApk = 47)!!.contains("diferente do anunciado"))
        assertTrue(problema(codigoApk = 49)!!.contains("diferente do anunciado"))
    }

    @Test
    fun `sha256Hex gera 64 caracteres hexadecimais minusculos`() {
        val hex = ConferenciaApk.sha256Hex(byteArrayOf(1, 2, 3))
        assertEquals(64, hex.length)
        assertTrue(hex.all { it in "0123456789abcdef" })
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", ConferenciaApk.sha256Hex(ByteArray(0)))
    }
}
