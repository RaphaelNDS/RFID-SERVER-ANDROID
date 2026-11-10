package com.example.rfid_server.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.R
import com.example.rfid_server.dados.LidaInventario
import com.example.rfid_server.sincronizacao.FilaTransferencias
import com.google.android.material.card.MaterialCardView

/**
 * Etiquetas lidas no inventário (aba Lidas). As fora da lista (lidas mas não esperadas no local) aparecem com
 * fundo de atenção e o texto "fora da lista"; tocar numa delas chama [aoTocarForaDaLista] (Localizar ou, para quem
 * pode, Transferir para este local). A transferência pedida aparece numa linha própria (aguardando, feita, recusada).
 */
class LidaInventarioAdapter(private val aoTocarForaDaLista: (LidaInventario) -> Unit) :
    ListAdapter<LidaInventario, LidaInventarioAdapter.VH>(Diferenca { it.epc }) {

    /** O operador da sessão pode transferir (podeTransferir no login, servidor 1.7.0): muda o "Toque para...". */
    var podeTransferir = false
        set(valor) {
            if (field == valor) return
            field = valor
            notifyItemRangeChanged(0, itemCount)
        }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cartao: MaterialCardView = view as MaterialCardView
        val texto: TextView = view.findViewById(R.id.txtLida)
        val foraDaLista: TextView = view.findViewById(R.id.txtForaDaLista)
        val transferencia: TextView = view.findViewById(R.id.txtTransferencia)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_lida_inventario, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context

        // Fora da lista com a ficha consultada no servidor: patrimônio e "Tipo · Marca · Modelo" do cadastro
        val descricao = item.ficha?.let { listOfNotNull(it.tipo, it.marca, it.modelo).joinToString(" · ") }.orEmpty()
        holder.texto.text = (item.patrimonioConhecido?.let { ctx.getString(R.string.inventario_lida_com_patrimonio, it, item.epc) } ?: item.epc) +
            if (descricao.isNotEmpty()) "\n$descricao" else ""
        holder.foraDaLista.isVisible = item.foraDaLista
        // Baixado ou extraviado no cadastro: em vermelho (não deveria estar circulando); sem cadastro: dito com clareza
        val alerta = item.ficha?.alertaSituacao()
        holder.foraDaLista.text = when {
            alerta != null -> ctx.getString(R.string.inventario_lida_alerta, alerta)
            item.semCadastro -> ctx.getString(R.string.inventario_lida_sem_cadastro)
            podeTransferir && item.podePedirTransferencia -> ctx.getString(R.string.inventario_lida_fora_da_lista_transferir)
            else -> ctx.getString(R.string.inventario_lida_fora_da_lista)
        }
        holder.foraDaLista.setTextColor(ContextCompat.getColor(ctx, if (alerta != null) R.color.erro else R.color.atencao))

        val transferencia = item.transferencia
        holder.transferencia.isVisible = transferencia != null
        if (transferencia != null) {
            holder.transferencia.text = when (transferencia.estado) {
                FilaTransferencias.Estado.PENDENTE -> ctx.getString(R.string.inventario_transferencia_pendente)
                FilaTransferencias.Estado.FEITA -> when {
                    transferencia.guia != null -> ctx.getString(R.string.inventario_transferencia_feita, transferencia.guia)
                    transferencia.jaEstava -> ctx.getString(R.string.inventario_transferencia_ja_estava)
                    else -> ctx.getString(R.string.inventario_transferencia_feita_sem_guia)
                }
                FilaTransferencias.Estado.RECUSADA ->
                    ctx.getString(R.string.inventario_transferencia_recusada, transferencia.mensagem.orEmpty())
            }
            holder.transferencia.setTextColor(ContextCompat.getColor(ctx, when (transferencia.estado) {
                FilaTransferencias.Estado.PENDENTE -> R.color.atencao
                FilaTransferencias.Estado.FEITA -> R.color.sucesso
                FilaTransferencias.Estado.RECUSADA -> R.color.erro
            }))
        }
        // Transferida: já é deste local no cadastro, o cartão sai do fundo de atenção
        val transferida = transferencia?.estado == FilaTransferencias.Estado.FEITA
        holder.cartao.setCardBackgroundColor(ContextCompat.getColor(ctx, when {
            transferida -> R.color.sucesso_fundo
            item.foraDaLista -> R.color.atencao_fundo
            else -> R.color.superficie
        }))

        if (item.foraDaLista) {
            holder.itemView.setOnClickListener { aoTocarForaDaLista(item) }
        } else {
            holder.itemView.setOnClickListener(null)
            holder.itemView.isClickable = false
        }
    }

    fun atualizar(novaLista: List<LidaInventario>) = submitList(novaLista)
}
