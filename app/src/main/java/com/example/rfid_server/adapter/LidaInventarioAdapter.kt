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
import com.google.android.material.card.MaterialCardView

/**
 * Etiquetas lidas no inventário (aba Lidas). As fora da lista (lidas mas não esperadas no local) aparecem com
 * fundo de atenção e o texto "fora da lista"; tocar numa delas chama [aoTocarForaDaLista] (abre o Localizar).
 */
class LidaInventarioAdapter(private val aoTocarForaDaLista: (LidaInventario) -> Unit) :
    ListAdapter<LidaInventario, LidaInventarioAdapter.VH>(Diferenca { it.epc }) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val cartao: MaterialCardView = view as MaterialCardView
        val texto: TextView = view.findViewById(R.id.txtLida)
        val foraDaLista: TextView = view.findViewById(R.id.txtForaDaLista)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_lida_inventario, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context

        holder.texto.text = item.patrimonio?.let { ctx.getString(R.string.inventario_lida_com_patrimonio, it, item.epc) } ?: item.epc
        holder.foraDaLista.isVisible = item.foraDaLista
        holder.cartao.setCardBackgroundColor(ContextCompat.getColor(ctx, if (item.foraDaLista) R.color.atencao_fundo else R.color.superficie))

        if (item.foraDaLista) {
            holder.itemView.setOnClickListener { aoTocarForaDaLista(item) }
        } else {
            holder.itemView.setOnClickListener(null)
            holder.itemView.isClickable = false
        }
    }

    fun atualizar(novaLista: List<LidaInventario>) = submitList(novaLista)
}
