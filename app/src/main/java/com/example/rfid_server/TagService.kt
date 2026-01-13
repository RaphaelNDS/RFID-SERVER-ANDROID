package com.example.rfid_server

import android.content.Context
import android.util.Log
import com.example.rfid_server.view.TagView
import kotlinx.serialization.Contextual
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class TagService(private val context: Context) {

    private val client = OkHttpClient()

    private fun baseUrl() = AppConfig.getBaseUrl(context)

    fun testarConexao(callback: (Boolean) -> Unit) {

        val url = "${AppConfig.getBaseUrl(context)}/api/status"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                callback(false)
            }

            override fun onResponse(call: Call, response: Response) {
                callback(response.isSuccessful)
            }
        })
    }

    fun buscarTag(epc: String, callback: (TagView?) -> Unit) {

        val url = "${baseUrl()}/api/tags/$epc"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                Log.e("TAG_SERVICE", "Erro ao buscar tag", e)
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {

                if (!response.isSuccessful) {
                    callback(null)
                    return
                }

                val json = JSONObject(response.body!!.string())

                val tag = TagView(
                    epc = epc,
                    codigoInterno = json.getString("codigoInterno"),
                    modelo = json.getString("modelo"),
                    patrimonio = json.getString("patrimonio"),
                    numeroSerie = json.getString("numeroSerie")
                )

                callback(tag)
            }
        })
    }

    fun enviarNaoCadastrada(epc: String) {

        val url = "${baseUrl()}/api/tags/naocadastrada"

        val body = JSONObject()
            .put("tag", epc)
            .toString()
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("TAG_SERVICE", "Erro ao enviar não cadastrada", e)
            }

            override fun onResponse(call: Call, res: Response) {
                Log.d("TAG_SERVICE", "Não cadastrada enviada: ${res.code}")
            }
        })
    }
}

