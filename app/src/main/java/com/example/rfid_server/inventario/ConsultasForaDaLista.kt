package com.example.rfid_server.inventario

/**
 * Quais etiquetas fora da lista consultar no servidor (ficha do bem): cada uma uma vez só, no máximo [maximo] por
 * inventário (uma sala inteira fora da lista não vira centenas de consultas), e a que falhou (sem rede) só de novo
 * depois de [esperaFalhaMs]. Sem dependência do Android, para os testes; usado só na thread principal.
 */
class ConsultasForaDaLista(private val maximo: Int = 300, private val esperaFalhaMs: Long = 60_000) {
    private val pedidas = HashSet<String>()
    private val falhas = HashMap<String, Long>()

    /** Marca e devolve as de [epcs] que devem ser consultadas agora. */
    fun paraConsultar(epcs: List<String>, agora: Long): List<String> {
        val novas = ArrayList<String>()
        for (epc in epcs) {
            if (epc in pedidas) continue
            val falhou = falhas[epc]
            if (falhou != null && agora - falhou < esperaFalhaMs) continue
            // No limite, só as novas ficam de fora: as que falharam antes (mais abaixo na lista, que vem com as mais
            // novas primeiro) continuam sendo tentadas de novo. Com "break" elas nunca mais eram pedidas
            if (falhou == null && pedidas.size + falhas.size >= maximo) continue
            falhas.remove(epc)
            pedidas += epc
            novas += epc
        }
        return novas
    }

    /** A consulta não deu certo (sem rede, servidor fora): pode ser pedida de novo depois da espera. */
    fun falhou(epc: String, agora: Long) {
        pedidas.remove(epc)
        falhas[epc] = agora
    }

    /** Novo inventário: tudo de novo. */
    fun limpar() {
        pedidas.clear()
        falhas.clear()
    }
}
