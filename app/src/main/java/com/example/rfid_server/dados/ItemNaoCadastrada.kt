package com.example.rfid_server.dados

/** Linha da aba "Não cadastradas": a etiqueta e, se houver, a situação do cadastro feito no coletor. */
data class ItemNaoCadastrada(val epc: String, val situacao: String) {

    companion object {
        const val SEM_CADASTRO = "Toque para cadastrar"

        /** Junta as leituras não cadastradas com os cadastros guardados no coletor (mesma ordem das leituras). */
        fun montar(leituras: List<LeituraEntity>, cadastros: List<CadastroEntity>): List<ItemNaoCadastrada> {
            val porEpc = cadastros.associateBy { it.epc }

            return leituras.map { leitura ->
                val cadastro = porEpc[leitura.epc]
                ItemNaoCadastrada(
                    epc = leitura.epc,
                    situacao = when (cadastro?.situacao) {
                        null -> SEM_CADASTRO
                        SituacaoCadastro.PENDENTE -> "Cadastro aguardando envio ao servidor" +
                            (cadastro.patrimonio.takeIf { it.isNotBlank() }?.let { " (patrimônio $it)" } ?: "")
                        SituacaoCadastro.RECUSADO -> "Cadastro recusado: ${cadastro.erro ?: "sem motivo"} — toque para corrigir"
                    }
                )
            }
        }
    }
}
