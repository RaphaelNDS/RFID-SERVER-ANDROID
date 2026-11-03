package com.example.rfid_server.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.R

/** Lista simples de textos (ex.: etiquetas lidas no inventário). Cada texto aparece uma vez só. */
class TagNaoAdapter : ListAdapter<String, TagNaoAdapter.VH>(Diferenca { it }) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val txt: TextView = view.findViewById(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_lista_simples, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.txt.text = getItem(position)
    }

    fun atualizar(novaLista: List<String>) = submitList(novaLista)
}
