package com.example.rfid_server.service

import android.content.Context
import android.util.Log
import com.example.rfid_server.AppConfig
import com.example.rfid_server.view.TagView
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

class TagService(private val context: Context) {

    private val client = OkHttpClient()

    private fun baseUrl() = AppConfig.getBaseUrl(context)

    fun testarConexao(callback: (Boolean) -> Unit) {

        val url = "${AppConfig.getBaseUrl(context)}/api/tags/ping"

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
                    tipo = json.optString("tipo", null),
                    marca = json.optString("marca", null),
                    modelo = json.optString("modelo", null),
                    patrimonio = json.getString("patrimonio"),
                    numeroSerie = json.getString("numeroSerie")
                )

                callback(tag)
            }
        })
    }

    fun buscarTagPorPatrimonio(patrimonio: String, callback: (String?) -> Unit) {

        val url = "${baseUrl()}/api/tags/patrimonio/$patrimonio"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {

                if (!response.isSuccessful) {
                    callback(null)
                    return
                }

                val json = JSONObject(response.body!!.string())
                val epc = json.getString("epc")

                callback(epc)
            }
        })
    }



    fun enviarNaoCadastrada(epc: String) {

        val url = "${baseUrl()}/api/rfid/naocadastrada"

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
