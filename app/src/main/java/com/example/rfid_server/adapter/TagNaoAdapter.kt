package com.example.rfid_server.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class TagNaoAdapter(private val list: MutableList<String>) :
    RecyclerView.Adapter<TagNaoAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val txt = view.findViewById<TextView>(android.R.id.text1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_1, parent, false)
        return VH(v)
    }

    override fun getItemCount() = list.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.txt.text = list[position]
    }

    fun add(epc: String) {
        list.add(0, epc)
        notifyItemInserted(0)
    }
}
