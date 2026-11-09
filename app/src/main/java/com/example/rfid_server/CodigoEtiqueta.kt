package com.example.rfid_server

import java.security.MessageDigest

/**
 * Código (EPC de 96 bits, 24 hexadecimais) que o coletor grava numa etiqueta ao cadastrar o bem, escolhido em 09/11:
 * prefixo da empresa + patrimônio, sem travar a etiqueta.
 *
 *  - patrimônio de até 9 caracteres ASCII visíveis: "424B50" ("BKP") + o patrimônio em ASCII, completado com 00.
 *    Dá para ler o patrimônio direto do código (ex.: 0123/2019 → 424B50 303132332F32303139);
 *  - patrimônio maior ou com acento: "424B48" ("BKH") + os 18 primeiros hexadecimais do SHA-256 do patrimônio.
 *
 * Os dois começam com [FiltroLeitura.PREFIXO_EMPRESA] ("BK"). O patrimônio entra normalizado (maiúsculas, sem espaços
 * nas pontas, como o Cadastro guarda), então o mesmo bem gera sempre o mesmo código. O servidor recusa EPC repetido.
 */
object CodigoEtiqueta {

    private const val PREFIXO_TEXTO = "424B50"   // "BKP"
    private const val PREFIXO_RESUMO = "424B48"  // "BKH"
    private const val BYTES_TEXTO = 9

    /** Palavra PC para EPC de 96 bits (6 palavras de 16 bits: 6 shl 11 = 0x3000), sem memória de usuário nem AFI. */
    const val PC_96_BITS = "3000"

    /**
     * PC para 96 bits a partir do PC atual da etiqueta: troca só o tamanho (bits 15 a 11 = 6 palavras) e preserva
     * os outros bits (memória de usuário, XI, AFI). Sem o PC atual, o padrão [PC_96_BITS].
     */
    fun pcPara96Bits(pcAtual: Int?): String =
        if (pcAtual == null) PC_96_BITS else "%04X".format((pcAtual and 0x07FF) or 0x3000)

    /** EPC em hexadecimal maiúsculo (24 caracteres); null se o patrimônio estiver vazio. */
    fun gerar(patrimonio: String): String? {
        val valor = patrimonio.trim().uppercase()
        if (valor.isEmpty()) return null
        val ascii = valor.all { it in ' '..'~' }
        return if (ascii && valor.length <= BYTES_TEXTO) {
            PREFIXO_TEXTO + hex(valor.toByteArray(Charsets.US_ASCII)).padEnd(BYTES_TEXTO * 2, '0')
        } else {
            PREFIXO_RESUMO + hex(MessageDigest.getInstance("SHA-256").digest(valor.toByteArray(Charsets.UTF_8))).take(BYTES_TEXTO * 2)
        }
    }

    /** O EPC já foi gravado pelo coletor (prefixo da empresa)? */
    fun daEmpresa(epc: String): Boolean = epc.uppercase().startsWith(FiltroLeitura.PREFIXO_EMPRESA)

    /** Patrimônio legível de um código "BKP" (null para os de resumo, "BKH", ou de outra origem). */
    fun patrimonioDe(epc: String): String? {
        val codigo = epc.uppercase()
        if (codigo.length != 24 || !codigo.startsWith(PREFIXO_TEXTO)) return null
        val bytes = codigo.substring(PREFIXO_TEXTO.length).chunked(2).map { it.toInt(16).toByte() }.takeWhile { it != 0.toByte() }
        return String(bytes.toByteArray(), Charsets.US_ASCII).ifEmpty { null }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02X".format(it) }
}
