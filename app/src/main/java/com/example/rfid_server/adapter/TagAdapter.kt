package com.example.rfid_server.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.R
import com.example.rfid_server.view.TagView

class TagAdapter(private val list: MutableList<TagView>) :
    RecyclerView.Adapter<TagAdapter.VH>() {

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
        val t = list[position]
        holder.txt.text =
            "${t.modelo} - ${t.patrimonio} - ${t.numeroSerie} - EPC: ${t.epc}"
    }

//    fun add(tag: TagView) {
//        list.add(0, tag)
//        notifyItemInserted(0)
//    }

    fun add(item: TagView) {
        list.add(0, item)
        notifyItemInserted(0)
    }

}
