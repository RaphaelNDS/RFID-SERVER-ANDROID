package com.example.rfid_server

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PIN das Configurações do coletor: protege o IP do servidor, a chave do coletor e a confirmação do
 * certificado contra quem pega o coletor na mão. Só o hash (PBKDF2 com sal aleatório) fica no aparelho.
 * A cada [MAX_ERROS] erros as tentativas ficam bloqueadas por um tempo crescente ([duracaoBloqueio]).
 * O contador de erros só volta a zero com o PIN certo ou com a liberação por um ADMIN.
 */
object PinColetor {

    const val TAMANHO_MINIMO = 4
    const val TAMANHO_MAXIMO = 8
    const val MAX_ERROS = 5

    /** Bloqueio crescente: 5 min, 15 min, 1 h e daí em diante 1 h a cada [MAX_ERROS] erros. */
    private val DURACOES_BLOQUEIO_MS = longArrayOf(5L * 60_000, 15L * 60_000, 60L * 60_000)

    /** Duração do bloqueio das versões até a 1.6.1 (estado antigo, gravado sem a duração). */
    const val BLOQUEIO_ANTIGO_MS = 5L * 60_000

    // Lento de propósito: com só 10^4 a 10^8 PINs possíveis, cada tentativa num hash copiado do aparelho
    // precisa custar caro. ~50 ms por conferência no MC3300x, que só acontece ao salvar as Configurações.
    private const val ITERACOES = 20_000

    fun formatoValido(pin: String): Boolean = pin.length in TAMANHO_MINIMO..TAMANHO_MAXIMO && pin.all { it in '0'..'9' }

    /** Problema do PIN novo para mostrar ao responsável, ou null se estiver bom. */
    fun problemaDoNovo(pin: String, confirmacao: String): String? = when {
        !formatoValido(pin) -> "O PIN precisa ter de $TAMANHO_MINIMO a $TAMANHO_MAXIMO números"
        pin.toSet().size == 1 -> "PIN fácil demais: não use o mesmo número repetido"
        pin in "0123456789" || pin in "9876543210" -> "PIN fácil demais: não use números em sequência"
        pin != confirmacao -> "Os dois PINs não são iguais"
        else -> null
    }

    fun novoSal(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.let { Base64.getEncoder().encodeToString(it) }

    fun hash(pin: String, sal: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), Base64.getDecoder().decode(sal), ITERACOES, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(bytes)
    }

    /** Comparação em tempo constante. */
    fun confere(pin: String, sal: String, hashGuardado: String): Boolean =
        MessageDigest.isEqual(hash(pin, sal).toByteArray(), hashGuardado.toByteArray())

    /** Duração do [nivel]-ésimo bloqueio (1 = primeiro). */
    fun duracaoBloqueio(nivel: Int): Long = DURACOES_BLOQUEIO_MS[(nivel - 1).coerceIn(0, DURACOES_BLOQUEIO_MS.size - 1)]

    /**
     * Controle das tentativas erradas, como fica gravado nas preferências.
     * - [erros]: erros acumulados desde o último PIN certo ou liberação do ADMIN (não zera no bloqueio).
     * - [bloqueadoAte]: relógio de parede (epoch ms) do fim do bloqueio; 0 = sem bloqueio.
     * - [inicioDecorrido]: SystemClock.elapsedRealtime() no começo do bloqueio; -1 = desconhecido (estado antigo).
     * - [duracao]: duração do bloqueio em ms; 0 = desconhecida (estado antigo: vale [BLOQUEIO_ANTIGO_MS]).
     */
    data class EstadoTentativas(
        val erros: Int = 0,
        val bloqueadoAte: Long = 0,
        val inicioDecorrido: Long = -1,
        val duracao: Long = 0
    )

    /**
     * Quanto falta do bloqueio em ms (0 = pode tentar). Bloqueado se QUALQUER das duas medidas disser que sim:
     * - relógio de parede ([agoraParede]): limitado à duração do bloqueio, para um relógio atrasado (de propósito
     *   ou por engano) não prender o PIN por dias;
     * - tempo desde a ligação do aparelho ([agoraDecorrido], elapsedRealtime), que não muda ao mexer no relógio.
     *   Se ele for menor que o do começo do bloqueio, o aparelho reiniciou: conta o bloqueio a partir da ligação
     *   (o tempo real passado é sempre maior ou igual a isso), então reiniciar nunca encurta o bloqueio.
     */
    fun restanteBloqueio(estado: EstadoTentativas, agoraParede: Long, agoraDecorrido: Long): Long {
        if (estado.bloqueadoAte <= 0) return 0
        val duracao = if (estado.duracao > 0) estado.duracao else BLOQUEIO_ANTIGO_MS
        val pelaParede = (estado.bloqueadoAte - agoraParede).coerceIn(0, duracao)
        val pelaLigacao = when {
            estado.inicioDecorrido < 0 -> 0L
            agoraDecorrido >= estado.inicioDecorrido -> duracao - (agoraDecorrido - estado.inicioDecorrido)
            else -> duracao - agoraDecorrido   // reiniciou: conta desde a ligação
        }.coerceIn(0, duracao)
        return maxOf(pelaParede, pelaLigacao)
    }

    /** Estado depois de mais um PIN errado: soma o erro e, a cada [MAX_ERROS], bloqueia por um tempo maior. */
    fun aposErro(estado: EstadoTentativas, agoraParede: Long, agoraDecorrido: Long): EstadoTentativas {
        val erros = estado.erros + 1
        if (erros % MAX_ERROS != 0) return estado.copy(erros = erros)
        val duracao = duracaoBloqueio(erros / MAX_ERROS)
        return EstadoTentativas(erros, agoraParede + duracao, agoraDecorrido, duracao)
    }

    /** Tentativas que ainda restam antes do próximo bloqueio. */
    fun restantesAteBloqueio(erros: Int): Int = MAX_ERROS - erros % MAX_ERROS
}
