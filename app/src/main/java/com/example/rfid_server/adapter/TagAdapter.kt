package com.example.rfid_server.adapter

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.R

/**
 * Aba "Leitura": etiquetas cadastradas (cartão com o patrimônio em destaque e os dados do equipamento embaixo) e,
 * em estilo discreto, as lidas que ainda esperam o servidor identificar (coletor sem rede).
 */
class TagAdapter(
    /** Toque numa etiqueta cadastrada: abre a ficha do bem. */
    private val aoTocar: (com.example.rfid_server.view.TagView) -> Unit = {}
) : ListAdapter<ItemLeitura, RecyclerView.ViewHolder>(Diferenca { it.chave }) {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val txtPatrimonio: TextView = view.findViewById(R.id.txtPatrimonio)
        val txtDescricao: TextView = view.findViewById(R.id.txtDescricao)
        val txtCodigos: TextView = view.findViewById(R.id.txtCodigos)
        val txtAlerta: TextView = view.findViewById(R.id.txtAlerta)
    }

    class VHAguardando(view: View) : RecyclerView.ViewHolder(view) {
        val txt: TextView = view.findViewById(android.R.id.text1)
    }

    override fun getItemViewType(position: Int): Int =
        if (getItem(position) is ItemLeitura.Aguardando) TIPO_AGUARDANDO else TIPO_CADASTRADA

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TIPO_AGUARDANDO) VHAguardando(inflater.inflate(R.layout.item_aguardando, parent, false))
        else VH(inflater.inflate(R.layout.item_tag, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val ctx = holder.itemView.context
        when (val item = getItem(position)) {
            is ItemLeitura.Aguardando -> (holder as VHAguardando).txt.text = ctx.getString(R.string.leitura_aguardando_servidor, item.epc)

            is ItemLeitura.Cadastrada -> {
                val h = holder as VH
                h.itemView.setOnClickListener { aoTocar(item.tag) }
                val linhas = ItemLeitura.LinhasTag.de(item.tag)

                // Linha 1: patrimônio em destaque (é o número que o operador confere na plaqueta)
                if (linhas.patrimonio != null) {
                    h.txtPatrimonio.text = ctx.getString(R.string.tag_patrimonio, linhas.patrimonio)
                    h.txtPatrimonio.setTextColor(ContextCompat.getColor(ctx, R.color.primaria))
                    h.txtPatrimonio.setTypeface(null, Typeface.BOLD)
                } else {
                    h.txtPatrimonio.setText(R.string.tag_sem_patrimonio)
                    h.txtPatrimonio.setTextColor(ContextCompat.getColor(ctx, R.color.texto_secundario))
                    h.txtPatrimonio.setTypeface(null, Typeface.ITALIC)
                }

                // Linha 2: Tipo · Marca · Modelo
                h.txtDescricao.text = linhas.descricao ?: ctx.getString(R.string.tag_sem_descricao)

                // Linha 3: código interno e número de série
                h.txtCodigos.text = if (linhas.serie != null) ctx.getString(R.string.tag_codigo_serie, linhas.codigo, linhas.serie)
                    else ctx.getString(R.string.tag_codigo, linhas.codigo)

                // Linha 4 (só quando há): bem que não deveria estar aqui (baixado, extraviado, vendido) ou alugado com a
                // devolução não registrada, em vermelho
                val alerta = item.tag.alertaSituacao()
                h.txtAlerta.visibility = if (alerta != null) View.VISIBLE else View.GONE
                h.txtAlerta.text = alerta?.let {
                    ctx.getString(if (item.tag.situacao == "ALUGADO") R.string.tag_alerta_alugado else R.string.tag_alerta_situacao, it)
                }
            }
        }
    }

    fun atualizar(novaLista: List<ItemLeitura>) = submitList(novaLista)

    private companion object {
        const val TIPO_CADASTRADA = 0
        const val TIPO_AGUARDANDO = 1
    }
}
