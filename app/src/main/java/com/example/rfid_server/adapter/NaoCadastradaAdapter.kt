package com.example.rfid_server.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.dados.ItemNaoCadastrada

/** Aba "Não cadastradas": EPC + situação do cadastro. Tocar abre o cadastro da etiqueta. */
class NaoCadastradaAdapter(
    private val aoTocar: (String) -> Unit
) : RecyclerView.Adapter<NaoCadastradaAdapter.VH>() {

    private var list: List<ItemNaoCadastrada> = emptyList()

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val epc: TextView = view.findViewById(android.R.id.text1)
        val situacao: TextView = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return VH(v)
    }

    override fun getItemCount() = list.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = list[position]
        holder.epc.text = item.epc
        holder.situacao.text = item.situacao
        holder.itemView.setOnClickListener { aoTocar(item.epc) }
    }

    fun atualizar(novaLista: List<ItemNaoCadastrada>) {
        list = novaLista
        notifyDataSetChanged()
    }
}
