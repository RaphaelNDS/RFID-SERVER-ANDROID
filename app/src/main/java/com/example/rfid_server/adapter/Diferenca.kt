package com.example.rfid_server.adapter

import androidx.recyclerview.widget.DiffUtil

/**
 * Compara duas versões de uma lista para o RecyclerView redesenhar só o que mudou.
 *
 * Antes, cada etiqueta lida chamava notifyDataSetChanged: a lista inteira era redesenhada (e a rolagem
 * pulava), o que pesava com centenas de itens. Com o ListAdapter, a diferença é calculada fora da thread
 * principal e só a linha nova (ou a que mudou) é desenhada.
 *
 * [chave] identifica o item (o EPC); o conteúdo é comparado pelo equals (data class).
 */
class Diferenca<T : Any>(private val chave: (T) -> Any) : DiffUtil.ItemCallback<T>() {

    override fun areItemsTheSame(antigo: T, novo: T): Boolean = chave(antigo) == chave(novo)

    @Suppress("DiffUtilEquals") // os itens são data class ou String: equals compara o conteúdo
    override fun areContentsTheSame(antigo: T, novo: T): Boolean = antigo == novo
}
