package com.example.rfid_server.view

data class TagView(
    val epc: String,
    val modelo: String,
    val patrimonio: String,
    val numeroSerie: String
)