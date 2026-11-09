package com.example.rfid_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodigoEtiquetaTest {

    @Test
    fun `patrimonio curto vira BKP mais o texto e volta legivel`() {
        val epc = CodigoEtiqueta.gerar(" 0123/2019 ")!!

        assertEquals("424B50303132332F32303139", epc)
        assertEquals(24, epc.length)
        assertEquals("0123/2019", CodigoEtiqueta.patrimonioDe(epc))
    }

    @Test
    fun `patrimonio menor e completado com zeros e maiusculas valem igual`() {
        assertEquals("424B505041542D3100000000", CodigoEtiqueta.gerar("pat-1"))
        assertEquals(CodigoEtiqueta.gerar("PAT-1"), CodigoEtiqueta.gerar("pat-1"))
        assertEquals("PAT-1", CodigoEtiqueta.patrimonioDe("424B505041542D3100000000"))
    }

    @Test
    fun `patrimonio longo ou com acento usa o resumo e continua com 24 hexadecimais`() {
        val longo = CodigoEtiqueta.gerar("PATRIMONIO-0000123456")!!
        val acento = CodigoEtiqueta.gerar("SALÃO-1")!!

        assertTrue(longo.startsWith("424B48"))
        assertTrue(acento.startsWith("424B48"))
        assertEquals(24, longo.length)
        assertEquals(24, acento.length)
        assertNotEquals(longo, CodigoEtiqueta.gerar("PATRIMONIO-0000123457"))
        assertNull("Resumo não volta para o patrimônio", CodigoEtiqueta.patrimonioDe(longo))
    }

    @Test
    fun `codigos gerados passam no filtro so da empresa e vazio nao gera`() {
        assertTrue(CodigoEtiqueta.daEmpresa(CodigoEtiqueta.gerar("1")!!))
        assertTrue(CodigoEtiqueta.daEmpresa(CodigoEtiqueta.gerar("PATRIMONIO-LONGO-1")!!))
        assertFalse(CodigoEtiqueta.daEmpresa("E28011606000020000000000"))
        assertNull(CodigoEtiqueta.gerar("   "))
        assertEquals("3000", CodigoEtiqueta.PC_96_BITS)
    }

    @Test
    fun `PC novo muda so o tamanho e preserva os outros bits`() {
        assertEquals("3000", CodigoEtiqueta.pcPara96Bits(null))
        assertEquals("3000", CodigoEtiqueta.pcPara96Bits(0x4000))  // 128 bits → 96
        assertEquals("3400", CodigoEtiqueta.pcPara96Bits(0x4400))  // memória de usuário (UMI) preservada
        assertEquals("3121", CodigoEtiqueta.pcPara96Bits(0x2121))  // ISO/AFI preservados
    }
}
