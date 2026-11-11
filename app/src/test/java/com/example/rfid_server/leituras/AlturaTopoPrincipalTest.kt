package com.example.rfid_server.leituras

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlturaTopoPrincipalTest {

    @Test
    fun `cabendo, o bloco fica com a altura natural`() {
        assertNull(AlturaTopoPrincipal.limite(alturaConteudo = 80, alturaBlocoMaisLista = 300, minimoLista = 120))
        // Exatamente no limite ainda cabe
        assertNull(AlturaTopoPrincipal.limite(alturaConteudo = 180, alturaBlocoMaisLista = 300, minimoLista = 120))
    }

    @Test
    fun `nao cabendo, o bloco encolhe e a lista fica com o minimo`() {
        // Pior caso no coletor (dp): 461 úteis - barra 56 - leitor 48 - divisores 2 - abas 48 - rodapé 64 = 243
        // para o bloco e a lista; recusa 73 + contadores 66 + sincronização 69 = 208 de avisos
        assertEquals(123, AlturaTopoPrincipal.limite(alturaConteudo = 208, alturaBlocoMaisLista = 243, minimoLista = 120))
    }

    @Test
    fun `tela menor que o minimo da lista esconde o bloco em vez de dar altura negativa`() {
        assertEquals(0, AlturaTopoPrincipal.limite(alturaConteudo = 50, alturaBlocoMaisLista = 100, minimoLista = 120))
    }

    @Test
    fun `sem avisos o bloco nao tem altura e nao e limitado`() {
        assertNull(AlturaTopoPrincipal.limite(alturaConteudo = 0, alturaBlocoMaisLista = 100, minimoLista = 120))
    }
}
