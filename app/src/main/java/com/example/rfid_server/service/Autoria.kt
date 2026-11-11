package com.example.rfid_server.service

/**
 * Quem fez e quando, para os envios da fila: o servidor confere se a sessão valia NAQUELE momento
 * (a ação pode ter sido feita offline, horas antes). [sessao] null = registro de antes do login de operador.
 */
data class Autoria(val sessao: String?, val momento: Long)
