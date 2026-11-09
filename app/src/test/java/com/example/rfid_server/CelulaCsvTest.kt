package com.example.rfid_server

import org.junit.Assert.assertEquals
import org.junit.Test

class CelulaCsvTest {

    @Test
    fun `texto comum passa igual`() {
        assertEquals("PAT-001", CelulaCsv.formatar("PAT-001"))
        assertEquals("", CelulaCsv.formatar(""))
    }

    @Test
    fun `ponto e virgula e aspas vao entre aspas`() {
        assertEquals("\"a;b\"", CelulaCsv.formatar("a;b"))
        assertEquals("\"monitor 24\"\"\"", CelulaCsv.formatar("monitor 24\""))
    }

    @Test
    fun `inicio de formula ganha apostrofo`() {
        assertEquals("'=HYPERLINK(1)", CelulaCsv.formatar("=HYPERLINK(1)"))
        assertEquals("'+55", CelulaCsv.formatar("+55"))
        assertEquals("'-10", CelulaCsv.formatar("-10"))
        assertEquals("'@soma", CelulaCsv.formatar("@soma"))
        assertEquals("\"'=A1;B1\"", CelulaCsv.formatar("=A1;B1"))
    }
}
