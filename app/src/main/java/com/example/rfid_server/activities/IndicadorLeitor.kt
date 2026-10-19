package com.example.rfid_server.activities

import android.content.res.ColorStateList
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.rfid_server.EstadoLeitor
import com.example.rfid_server.R
import com.example.rfid_server.RfidManager

/**
 * Liga a faixa de layout/include_estado_leitor.xml ao estado do leitor RFID: verde quando conectado,
 * amarela conectando e vermelha desconectado (com o botão "Reconectar", que não espera a tentativa automática).
 * Chame no onCreate de uma tela que tenha o include.
 */
fun AppCompatActivity.mostrarEstadoLeitor(rfidManager: RfidManager) {
    val painel = findViewById<View>(R.id.painelEstadoLeitor)
    val marca = findViewById<View>(R.id.marcaEstadoLeitor)
    val texto = findViewById<TextView>(R.id.txtEstadoLeitor)
    val botao = findViewById<Button>(R.id.btnReconectarLeitor)

    botao.setOnClickListener {
        botao.visibility = View.GONE
        rfidManager.reconectarAgora()
    }

    rfidManager.estado.observe(this) { estado ->
        texto.text = estado.descricao
        botao.visibility = if (estado == EstadoLeitor.DESCONECTADO) View.VISIBLE else View.GONE

        val (fundo, cor) = when (estado) {
            EstadoLeitor.CONECTADO -> R.color.sucesso_fundo to R.color.sucesso
            EstadoLeitor.CONECTANDO -> R.color.atencao_fundo to R.color.atencao
            EstadoLeitor.DESCONECTADO -> R.color.erro_fundo to R.color.erro
        }
        painel.setBackgroundColor(ContextCompat.getColor(this, fundo))
        texto.setTextColor(ContextCompat.getColor(this, cor))
        marca.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, cor))
    }
}
