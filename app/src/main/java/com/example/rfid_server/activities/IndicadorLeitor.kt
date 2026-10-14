package com.example.rfid_server.activities

import android.graphics.Color
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
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
            EstadoLeitor.CONECTADO -> "#E8F5E9" to "#2E7D32"
            EstadoLeitor.CONECTANDO -> "#FFF8E1" to "#F57F17"
            EstadoLeitor.DESCONECTADO -> "#FFEBEE" to "#C62828"
        }
        painel.setBackgroundColor(Color.parseColor(fundo))
        texto.setTextColor(Color.parseColor(cor))
    }
}
