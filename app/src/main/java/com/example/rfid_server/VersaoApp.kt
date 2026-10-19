package com.example.rfid_server

/** Versão instalada, no mesmo formato na tela e no log (ex.: "Versão 1.4.0 (código 13)"). */
object VersaoApp {

    fun descricao(): String = descricao(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE)

    /** A versão de desenvolvimento aparece marcada: não é a que deve estar nos coletores em uso. */
    internal fun descricao(nome: String, codigo: Int, tipoBuild: String): String =
        "Versão $nome (código $codigo)" + if (tipoBuild == "release") "" else " · desenvolvimento"
}
