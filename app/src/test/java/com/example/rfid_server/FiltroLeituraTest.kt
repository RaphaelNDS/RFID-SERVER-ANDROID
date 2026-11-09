package com.example.rfid_server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltroLeituraTest {

    @Test
    fun `sem filtro tudo passa`() {
        assertTrue(FiltroLeitura.aceita("E2801160600002", -80, rssiMinimo = null, soEmpresa = false))
    }

    @Test
    fun `alcance ignora sinal fraco e aceita sinal desconhecido`() {
        assertFalse("Sala vizinha (sinal fraco)", FiltroLeitura.aceita("E1", -70, rssiMinimo = -65, soEmpresa = false))
        assertTrue(FiltroLeitura.aceita("E1", -65, rssiMinimo = -65, soEmpresa = false))
        assertTrue(FiltroLeitura.aceita("E1", -40, rssiMinimo = -55, soEmpresa = false))
        assertTrue("Leitor sem RSSI: não descarta", FiltroLeitura.aceita("E1", 0, rssiMinimo = -55, soEmpresa = false))
    }

    @Test
    fun `so empresa aceita os dois formatos gravados pelo coletor`() {
        assertTrue(FiltroLeitura.aceita("424B50303132330000000000", -50, null, soEmpresa = true))
        assertTrue(FiltroLeitura.aceita("424b48aabbccddeeff001122", -50, null, soEmpresa = true))
        assertFalse("Crachá, embalagem, etiqueta antiga", FiltroLeitura.aceita("E28011606000020000000000", -50, null, soEmpresa = true))
    }
}
