package com.example.rfid_server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PinColetorTest {

    @Test
    fun `PIN certo confere e errado nao`() {
        val sal = PinColetor.novoSal()
        val hash = PinColetor.hash("4821", sal)

        assertTrue(PinColetor.confere("4821", sal, hash))
        assertFalse(PinColetor.confere("4822", sal, hash))
        assertFalse(PinColetor.confere("", sal, hash))
    }

    @Test
    fun `o hash nao guarda o PIN e muda com o sal`() {
        val sal1 = PinColetor.novoSal()
        val sal2 = PinColetor.novoSal()

        assertNotEquals(sal1, sal2)
        assertNotEquals(PinColetor.hash("4821", sal1), PinColetor.hash("4821", sal2))
        assertFalse(PinColetor.hash("4821", sal1).contains("4821"))
    }

    @Test
    fun `PIN novo precisa ter de 4 a 8 numeros e nao ser obvio`() {
        assertNull(PinColetor.problemaDoNovo("4821", "4821"))
        assertNull(PinColetor.problemaDoNovo("48213907", "48213907"))

        assertEquals("O PIN precisa ter de 4 a 8 números", PinColetor.problemaDoNovo("482", "482"))
        assertEquals("O PIN precisa ter de 4 a 8 números", PinColetor.problemaDoNovo("482139071", "482139071"))
        assertEquals("O PIN precisa ter de 4 a 8 números", PinColetor.problemaDoNovo("48a1", "48a1"))
        assertEquals("PIN fácil demais: não use o mesmo número repetido", PinColetor.problemaDoNovo("0000", "0000"))
        assertEquals("PIN fácil demais: não use números em sequência", PinColetor.problemaDoNovo("1234", "1234"))
        assertEquals("PIN fácil demais: não use números em sequência", PinColetor.problemaDoNovo("8765", "8765"))
        assertEquals("Os dois PINs não são iguais", PinColetor.problemaDoNovo("4821", "4812"))
    }

    @Test
    fun `bloqueia so a partir do quinto erro seguido`() {
        assertNull(PinColetor.bloqueioAposErro(4, agora = 1_000))
        assertEquals(1_000 + PinColetor.BLOQUEIO_MS, PinColetor.bloqueioAposErro(5, agora = 1_000))
    }
}
