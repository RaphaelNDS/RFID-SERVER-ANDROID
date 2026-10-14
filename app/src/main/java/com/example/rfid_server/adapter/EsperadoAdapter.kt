package com.example.rfid_server.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.R
import com.example.rfid_server.dados.ItemEsperado

/** Etiquetas esperadas no local que ainda não foram lidas. Tocar num item chama [aoTocar] (abre o Localizar). */
class EsperadoAdapter(private val aoTocar: (ItemEsperado) -> Unit) : RecyclerView.Adapter<EsperadoAdapter.VH>() {

    private var lista: List<ItemEsperado> = emptyList()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val patrimonio: TextView = view.findViewById(R.id.txtPatrimonio)
        val descricao: TextView = view.findViewById(R.id.txtDescricao)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_esperado, parent, false))

    override fun getItemCount() = lista.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = lista[position]

        holder.patrimonio.text = item.patrimonio?.let { "Patrimônio $it" } ?: "Sem patrimônio (EPC ${item.epc})"
        holder.descricao.text = item.descricao().ifEmpty { item.codigoInterno?.let { "Código $it" } ?: item.epc }
        holder.itemView.setOnClickListener { aoTocar(item) }
    }

    fun atualizar(novaLista: List<ItemEsperado>) {
        lista = novaLista
        notifyDataSetChanged()
    }
}
