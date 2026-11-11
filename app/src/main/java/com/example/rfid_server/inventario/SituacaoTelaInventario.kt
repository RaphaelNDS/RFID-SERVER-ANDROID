package com.example.rfid_server.inventario

import com.example.rfid_server.dados.ComparacaoInventario
import com.example.rfid_server.dados.EsperadoLocal
import com.example.rfid_server.dados.ItemEsperado
import com.example.rfid_server.dados.LidaInventario

/** O que aparece na tela de inventário depois de cada cálculo (feito fora da thread principal). */
data class SituacaoTelaInventario(
    /** Null: sem lista do esperado para comparar (primeiro inventário, servidor sem a lista, ainda buscando). */
    val comparacao: ComparacaoInventario?,
    val totalLidas: Int,
    /** Já filtradas pelo termo. */
    val lidas: List<LidaInventario>,
    /** Já filtradas pelo termo. */
    val faltando: List<ItemEsperado>
) {
    companion object {
        fun calcular(
            esperado: EsperadoLocal?,
            patrimoniosEsperados: Map<String, String?>,
            epcsLidos: List<String>,
            termo: String
        ): SituacaoTelaInventario {
            val referencia = esperado?.takeIf { it.temReferencia }
            val comparacao = referencia?.let { ComparacaoInventario.comparar(it, epcsLidos) }
            val lidas = LidaInventario.classificar(epcsLidos, if (referencia != null) patrimoniosEsperados else null)

            return SituacaoTelaInventario(
                comparacao = comparacao,
                totalLidas = epcsLidos.size,
                lidas = FiltroInventario.lidas(lidas, termo),
                faltando = FiltroInventario.faltando(comparacao?.faltando.orEmpty(), termo)
            )
        }
    }
}
