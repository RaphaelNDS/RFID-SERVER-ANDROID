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

    private val min = 60_000L
    private val parede = 1_700_000_000_000L   // relógio de parede de exemplo
    private val ligado = 3_600_000L            // elapsedRealtime de exemplo (1 h ligado)

    /** Aplica [n] erros seguidos no mesmo instante. */
    private fun errar(n: Int, estado: PinColetor.EstadoTentativas = PinColetor.EstadoTentativas()) =
        (1..n).fold(estado) { e, _ -> PinColetor.aposErro(e, parede, ligado) }

    @Test
    fun `bloqueia so a partir do quinto erro`() {
        val quatro = errar(4)
        assertEquals(4, quatro.erros)
        assertEquals(0, PinColetor.restanteBloqueio(quatro, parede, ligado))
        assertEquals(1, PinColetor.restantesAteBloqueio(4))

        val cinco = errar(5)
        assertEquals(5, cinco.erros)   // não zera no bloqueio
        assertEquals(5 * min, PinColetor.restanteBloqueio(cinco, parede, ligado))
    }

    @Test
    fun `bloqueio cresce 5 min, 15 min, 1 h e fica em 1 h`() {
        assertEquals(5 * min, errar(5).duracao)
        assertEquals(15 * min, errar(10).duracao)
        assertEquals(60 * min, errar(15).duracao)
        assertEquals(60 * min, errar(20).duracao)
        assertEquals(60 * min, errar(50).duracao)
        // Entre bloqueios: cada erro só soma, sem bloquear de novo
        assertEquals(5 * min, errar(7).duracao)
        assertEquals(3, PinColetor.restantesAteBloqueio(7))
    }

    @Test
    fun `sem bloqueio e livre`() {
        assertEquals(0, PinColetor.restanteBloqueio(PinColetor.EstadoTentativas(), parede, ligado))
    }

    @Test
    fun `bloqueio acaba quando as duas medidas passam do tempo`() {
        val bloqueado = errar(5)
        assertEquals(1 * min, PinColetor.restanteBloqueio(bloqueado, parede + 4 * min, ligado + 4 * min))
        assertEquals(0, PinColetor.restanteBloqueio(bloqueado, parede + 5 * min, ligado + 5 * min))
    }

    @Test
    fun `adiantar o relogio nao acaba com o bloqueio`() {
        val bloqueado = errar(5)
        // Relógio adiantado 1 dia, mas só 1 min de verdade passou desde o bloqueio
        assertEquals(4 * min, PinColetor.restanteBloqueio(bloqueado, parede + 24 * 60 * min, ligado + 1 * min))
    }

    @Test
    fun `atrasar o relogio nao prende o PIN mais que a duracao do bloqueio`() {
        val bloqueado = errar(5)
        // Relógio voltou 1 ano: no máximo a duração do bloqueio pela parede
        assertEquals(5 * min, PinColetor.restanteBloqueio(bloqueado, parede - 365L * 24 * 60 * min, ligado))
        assertEquals(0, PinColetor.restanteBloqueio(bloqueado, parede + 5 * min, ligado + 5 * min))
    }

    @Test
    fun `reiniciar o aparelho nao encurta o bloqueio`() {
        val bloqueado = errar(10)   // 15 min
        // Reiniciou (elapsedRealtime voltou a zero) e adiantou o relógio: conta desde a ligação
        assertEquals(13 * min, PinColetor.restanteBloqueio(bloqueado, parede + 24 * 60 * min, 2 * min))
        assertEquals(0, PinColetor.restanteBloqueio(bloqueado, parede + 24 * 60 * min, 15 * min))
        // Reiniciou sem mexer no relógio: vale a medida maior (a desde a ligação, por segurança)
        assertEquals(14 * min, PinColetor.restanteBloqueio(bloqueado, parede + 5 * min, 1 * min))
        assertEquals(0, PinColetor.restanteBloqueio(bloqueado, parede + 20 * min, 15 * min))
    }

    @Test
    fun `bloqueio vencido com o aparelho ligado e apagado e nao volta no proximo reinicio`() {
        val bloqueado = errar(5)
        // Ainda bloqueado: nada a limpar
        assertEquals(null, PinColetor.semBloqueioVencido(bloqueado, parede + 4 * min, ligado + 4 * min))

        // Venceu com o aparelho ligado: apaga o bloqueio e mantém os erros
        val limpo = PinColetor.semBloqueioVencido(bloqueado, parede + 6 * min, ligado + 6 * min)!!
        assertEquals(5, limpo.erros)
        assertEquals(0, limpo.bloqueadoAte)

        // Dias depois o aparelho reinicia (elapsedRealtime volta perto de zero): continua livre
        assertEquals(0, PinColetor.restanteBloqueio(limpo, parede + 3 * 24 * 60 * min, 1 * min))
        // Antes da correção, o estado não limpo bloqueava de novo depois do reinício
        assertEquals(4 * min, PinColetor.restanteBloqueio(bloqueado, parede + 3 * 24 * 60 * min, 1 * min))

        // O próximo erro continua a contagem: no 10º, bloqueio de 15 min
        val proximo = (6..10).fold(limpo) { e, _ -> PinColetor.aposErro(e, parede + 7 * min, ligado + 7 * min) }
        assertEquals(15 * min, PinColetor.restanteBloqueio(proximo, parede + 7 * min, ligado + 7 * min))
    }

    @Test
    fun `estado gravado pela versao antiga continua valendo pelo relogio`() {
        // 1.6.1: erros zerados no bloqueio e só o bloqueado_ate gravado
        val antigo = PinColetor.EstadoTentativas(erros = 0, bloqueadoAte = parede + 3 * min)
        assertEquals(3 * min, PinColetor.restanteBloqueio(antigo, parede, ligado))
        assertEquals(0, PinColetor.restanteBloqueio(antigo, parede + 3 * min, ligado))
        // Relógio atrasado: limitado aos 5 min do bloqueio antigo
        assertEquals(PinColetor.BLOQUEIO_ANTIGO_MS, PinColetor.restanteBloqueio(antigo, parede - 60 * min, ligado))
        // Próximo erro depois do bloqueio antigo começa a contar do zero
        assertEquals(1, PinColetor.aposErro(antigo, parede + 3 * min, ligado).erros)
    }

    // ================= liberação pela chave do coletor =================

    private val chave = "chave-do-coletor-0123456789"

    @Test
    fun `chave do coletor confere so com a chave guardada`() {
        assertTrue(PinColetor.chaveConfere(chave, chave))
        assertTrue(PinColetor.chaveConfere("  $chave \n", chave))
        assertFalse(PinColetor.chaveConfere(chave + "x", chave))
        assertFalse(PinColetor.chaveConfere(chave.dropLast(1), chave))
        assertFalse(PinColetor.chaveConfere(chave.uppercase(), chave))
        assertFalse(PinColetor.chaveConfere("", chave))
        // Sem chave guardada no aparelho, nada libera (nem a chave vazia)
        assertFalse(PinColetor.chaveConfere("", ""))
        assertFalse(PinColetor.chaveConfere("qualquer", ""))
        assertFalse(PinColetor.chaveConfere("   ", "   "))
    }

    @Test
    fun `chave certa libera o PIN`() {
        val estado = PinColetor.EstadoTentativas(erros = 3)

        assertEquals(PinColetor.LiberacaoPorChave.Liberado, PinColetor.liberarComChave(chave, chave, estado, parede, ligado))
    }

    @Test
    fun `chave errada conta como PIN errado e bloqueia no quinto erro somado aos do PIN`() {
        // 3 erros de PIN antes: a chave errada é o 4º e o 5º bloqueia por 5 min
        var estado = PinColetor.EstadoTentativas(erros = 3)

        val quarto = PinColetor.liberarComChave("errada", chave, estado, parede, ligado) as PinColetor.LiberacaoPorChave.Errada
        assertEquals(4, quarto.estado.erros)
        assertEquals(0, PinColetor.restanteBloqueio(quarto.estado, parede, ligado))
        estado = quarto.estado

        val quinto = PinColetor.liberarComChave("errada", chave, estado, parede, ligado) as PinColetor.LiberacaoPorChave.Errada
        assertEquals(5, quinto.estado.erros)
        assertEquals(5 * min, PinColetor.restanteBloqueio(quinto.estado, parede, ligado))
    }

    @Test
    fun `bloqueado nem a chave certa libera e nada muda`() {
        val bloqueado = PinColetor.aposErro(PinColetor.EstadoTentativas(erros = 4), parede, ligado)

        assertEquals(
            PinColetor.LiberacaoPorChave.Bloqueado(5 * min),
            PinColetor.liberarComChave(chave, chave, bloqueado, parede, ligado)
        )
        assertEquals(
            PinColetor.LiberacaoPorChave.Bloqueado(2 * min),
            PinColetor.liberarComChave("errada", chave, bloqueado, parede + 3 * min, ligado + 3 * min)
        )
        // Acabado o bloqueio, a chave certa libera
        assertEquals(
            PinColetor.LiberacaoPorChave.Liberado,
            PinColetor.liberarComChave(chave, chave, bloqueado, parede + 5 * min, ligado + 5 * min)
        )
    }

    @Test
    fun `sem chave guardada a liberacao pela chave so conta erro`() {
        val resultado = PinColetor.liberarComChave("", "", PinColetor.EstadoTentativas(), parede, ligado)

        assertEquals(1, (resultado as PinColetor.LiberacaoPorChave.Errada).estado.erros)
    }
}
