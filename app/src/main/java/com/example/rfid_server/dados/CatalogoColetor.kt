package com.example.rfid_server.dados

import org.json.JSONObject

data class ItemCatalogo(val id: Long, val nome: String)
data class MarcaCatalogo(val id: Long, val nome: String, val modelos: List<ItemCatalogo>)
data class TipoCatalogo(val id: Long, val nome: String, val marcas: List<MarcaCatalogo>)

/**
 * Catálogo (tipo → marca → modelo) recebido de GET /api/android/catalogo, e se este coletor pode
 * cadastrar. Guardado no coletor como o JSON original (AppConfig) para o cadastro funcionar offline.
 */
data class CatalogoColetor(val podeCadastrar: Boolean, val tipos: List<TipoCatalogo>) {

    companion object {
        /** Lança org.json.JSONException se o texto não for o JSON esperado. */
        fun deJson(json: String): CatalogoColetor {
            val raiz = JSONObject(json)
            val tipos = raiz.getJSONArray("tipos")

            return CatalogoColetor(
                podeCadastrar = raiz.getBoolean("podeCadastrar"),
                tipos = (0 until tipos.length()).map { i ->
                    val tipo = tipos.getJSONObject(i)
                    val marcas = tipo.getJSONArray("marcas")
                    TipoCatalogo(
                        id = tipo.getLong("id"),
                        nome = tipo.getString("nome"),
                        marcas = (0 until marcas.length()).map { j ->
                            val marca = marcas.getJSONObject(j)
                            val modelos = marca.getJSONArray("modelos")
                            MarcaCatalogo(
                                id = marca.getLong("id"),
                                nome = marca.getString("nome"),
                                modelos = (0 until modelos.length()).map { k ->
                                    val modelo = modelos.getJSONObject(k)
                                    ItemCatalogo(modelo.getLong("id"), modelo.getString("nome"))
                                }
                            )
                        }
                    )
                }
            )
        }
    }
}
