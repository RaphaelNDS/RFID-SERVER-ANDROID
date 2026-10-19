package com.example.rfid_server.activities

import android.app.Activity
import android.content.Intent
import android.util.Log
import com.example.rfid_server.SessaoAtual
import com.example.rfid_server.SessaoOperador

/**
 * Devolve a sessão do operador, ou manda para a tela de login (fechando as outras telas) se ninguém
 * estiver logado ou a sessão tiver expirado. Chame no onResume das telas que leem ou cadastram.
 */
fun Activity.exigirSessao(): SessaoOperador? {
    SessaoAtual.get(this)?.let { return it }

    Log.i("SESSAO", "${javaClass.simpleName} sem operador logado: voltando para a tela de login")
    startActivity(
        Intent(this, LoginActivity::class.java)
            .putExtra(LoginActivity.EXTRA_MOTIVO, "Sua sessão terminou. Entre de novo para continuar.")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    )
    finish()
    return null
}
