package com.example.rfid_server

import android.util.Log
import com.example.rfid_server.view.TagView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class TagService {

    private val client = OkHttpClient()

    fun buscarTag(epc: String, callback: (TagView?) -> Unit) {

        val url = "http://182.17.10.130:8082/api/tags/$epc"

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
                    Log.w("TAG_SERVICE", "Tag não encontrada: ${response.code}")
                    callback(null)
                    return
                }

                val json = JSONObject(response.body!!.string())

                val tag = TagView(
                    epc = epc,
                    modelo = json.getString("modelo"),
                    patrimonio = json.getString("patrimonio"),
                    numeroSerie = json.getString("numeroSerie")
                )

                callback(tag)
            }
        })
    }

    fun enviarNaoCadastrada(epc: String) {

        val url = "http://182.17.10.130:8082/api/tags/naocadastrada"

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
