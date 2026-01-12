package com.example.rfid_server.api

import retrofit2.http.Body
import retrofit2.http.POST

interface TagNaoApi {

    @POST("api/tags/naocadastrada")
    suspend fun enviarNaoCadastrada(@Body req: LeituraRequest)
}
