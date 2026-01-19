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
        val txtCodigoInterno: TextView = view.findViewById(R.id.txtCodigoInterno)
        val txtTipo: TextView = view.findViewById(R.id.txtTipo)
        val txtMarca: TextView = view.findViewById(R.id.txtMarca)
        val txtModelo: TextView = view.findViewById(R.id.txtModelo)
        val txtPatrimonio: TextView = view.findViewById(R.id.txtPatrimonio)
        val txtSerie: TextView = view.findViewById(R.id.txtSerie)
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_tag, parent, false)
        return VH(v)
    }

    override fun getItemCount() = list.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = list[position]

        holder.txtCodigoInterno.text = "Código: ${t.codigoInterno}"
        holder.txtTipo.text = "Tipo: ${t.tipo ?: "—"}"
        holder.txtMarca.text = "Marca: ${t.marca ?: "—"}"
        holder.txtModelo.text = "Modelo: ${t.modelo ?: "—"}"
        holder.txtPatrimonio.text = "Patrimônio: ${t.patrimonio}"
        holder.txtSerie.text = "Série: ${t.numeroSerie}"

    }

    fun add(item: TagView) {
        list.add(0, item)
        notifyItemInserted(0)
    }
}
