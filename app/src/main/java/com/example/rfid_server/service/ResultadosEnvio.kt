package com.example.rfid_server.service

import com.example.rfid_server.dados.EsperadoLocal

/**
 * Resposta de GET /api/android/inventarios/esperado. [esperado] null = 304: a lista guardada com o ETag enviado ainda
 * é a atual. [etag] null = servidor anterior à 1.4.4 (sem ETag): a lista vem inteira sempre, como antes.
 */
data class EsperadoBaixado(val esperado: EsperadoLocal?, val etag: String?)

/** Resposta do envio das justificativas: [invalidas] = tags recusadas de vez (tag ou motivo inválido). */
data class ResultadoJustificativas(val gravadas: Int, val invalidas: List<String>)

/**
 * Resposta da transferência de um bem para outro local (servidor 1.7.0). [guia] = nº da guia de transferência, null
 * quando o bem [jaEstava] no local (reenvio: nenhuma guia nova).
 */
data class ResultadoTransferencia(
    val local: String?,
    val situacao: String?,
    val situacaoRotulo: String?,
    val guia: Long?,
    val jaEstava: Boolean
)
