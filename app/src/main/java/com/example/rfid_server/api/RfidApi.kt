package com.example.rfid_server.api

import retrofit2.http.GET
import retrofit2.http.Path

interface RfidApi {

    @GET("api/tags/{epc}")
    suspend fun buscarTag(@Path("epc") epc: String): TagResponse?
}

data class TagResponse(
    val codigoInterno: String,
    val tipo: String?,
    val marca: String?,
    val modelo: String?,
    val patrimonio: String,
    val numeroSerie: String,
)
