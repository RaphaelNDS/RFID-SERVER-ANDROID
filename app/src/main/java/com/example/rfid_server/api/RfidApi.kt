package com.example.rfid_server.api

import retrofit2.http.Body
import retrofit2.http.POST

interface RfidApi {

    @POST("api/rfid/ler")
    suspend fun lerTag(@Body req: LeituraRequest): TagResponse?
}

data class LeituraRequest(val tag: String)

data class TagResponse(
    val modelo: String,
    val patrimonio: String,
    val numeroSerie: String
)
