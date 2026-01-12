package com.example.rfid_server.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.R
import com.example.rfid_server.TagNaoCadastrada

class NaoCadastradaAdapter :
    RecyclerView.Adapter<NaoCadastradaAdapter.VH>() {

    private val itens = mutableListOf<TagNaoCadastrada>()

    fun add(item: TagNaoCadastrada) {
        itens.add(0, item)
        notifyItemInserted(0)
    }

    fun clear() {
        itens.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_nao_cadastrado, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = itens[position]
        holder.tag.text = t.epc
        holder.data.text = t.dataHora
    }

    override fun getItemCount() = itens.size

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tag: TextView = v.findViewById(R.id.txtTag)
        val data: TextView = v.findViewById(R.id.txtData)
    }
}
