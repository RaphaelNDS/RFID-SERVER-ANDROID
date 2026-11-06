package com.example.rfid_server.activities

import android.content.Context
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AlertDialog

/**
 * Campo de texto dentro de um diálogo (senha, email, PIN, chave): o diálogo já abre com o cursor no campo e o
 * teclado na tela (o operador de luva não precisa tocar no campo antes) e o Concluir do teclado — ou o Enter do
 * teclado físico do coletor — vale como o botão principal do diálogo.
 */
object CampoDialogo {

    /** O que fazer com uma ação do teclado no campo do diálogo. */
    enum class RespostaTeclado { CONFIRMAR, CONSUMIR, IGNORAR }

    /**
     * Concluir do teclado na tela, ou Enter apertado no teclado físico: confirma. O Enter solto depois é só
     * consumido (o diálogo já foi confirmado ao apertar). Outras ações (ex.: Próximo) seguem o normal.
     */
    fun respostaTeclado(acao: Int, enter: Boolean, apertou: Boolean): RespostaTeclado = when {
        acao == EditorInfo.IME_ACTION_DONE -> RespostaTeclado.CONFIRMAR
        enter && apertou -> RespostaTeclado.CONFIRMAR
        enter -> RespostaTeclado.CONSUMIR
        else -> RespostaTeclado.IGNORAR
    }

    /** Chame no onShow do diálogo: foco no [campo] e teclado aberto. */
    fun focarComTeclado(dialogo: AlertDialog, campo: EditText) {
        campo.requestFocus()
        dialogo.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        // O modo acima vale quando a janela ganha o foco; se ela já ganhou, abre o teclado direto. Pedido explícito
        // (flag 0): o MC3300x tem teclado físico, e com ele o Android ignora o pedido implícito (SHOW_IMPLICIT) —
        // no coletor o diálogo do PIN abria sem teclado
        campo.post {
            val teclado = campo.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            teclado?.showSoftInput(campo, 0)
        }
    }

    /** Concluir (ou Enter) no [campo] aciona o botão principal do diálogo, com as mesmas conferências do toque. */
    fun confirmarPeloTeclado(dialogo: AlertDialog, campo: EditText) {
        campo.imeOptions = EditorInfo.IME_ACTION_DONE
        campo.setOnEditorActionListener { _, acao, evento ->
            val enter = evento?.keyCode == KeyEvent.KEYCODE_ENTER
            when (respostaTeclado(acao, enter, evento?.action == KeyEvent.ACTION_DOWN)) {
                RespostaTeclado.CONFIRMAR -> {
                    dialogo.getButton(AlertDialog.BUTTON_POSITIVE)?.takeIf { it.isEnabled }?.performClick()
                    true
                }
                RespostaTeclado.CONSUMIR -> true
                RespostaTeclado.IGNORAR -> false
            }
        }
    }

    /** Diálogo sem onShow próprio: foco, teclado e Concluir = botão principal. Mostra o diálogo. */
    fun mostrar(dialogo: AlertDialog, campo: EditText) {
        confirmarPeloTeclado(dialogo, campo)
        dialogo.setOnShowListener { focarComTeclado(dialogo, campo) }
        dialogo.show()
    }
}
