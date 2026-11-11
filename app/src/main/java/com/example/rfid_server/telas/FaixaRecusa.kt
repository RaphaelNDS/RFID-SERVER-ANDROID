package com.example.rfid_server.telas

import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.rfid_server.R
import com.example.rfid_server.dados.RecusaInventario
import com.example.rfid_server.dados.RepositorioInventario

/**
 * Faixa vermelha (layout include_aviso_recusa) com o inventário ou as etiquetas que o coletor desistiu de enviar
 * por recusa definitiva do servidor. Antes eles só sumiam da contagem "aguardando envio", sem aviso nenhum.
 * Mostra um aviso por vez; "Ciente" esconde aquele e mostra o próximo, se houver.
 */
object FaixaRecusa {

    fun ligar(activity: AppCompatActivity, repositorio: RepositorioInventario) {
        val painel = activity.findViewById<View>(R.id.painelAvisoRecusa)
        val texto = activity.findViewById<TextView>(R.id.txtAvisoRecusa)
        val ciente = activity.findViewById<Button>(R.id.btnCienteRecusa)

        repositorio.avisosRecusa.observe(activity) { avisos ->
            val aviso = avisos.firstOrNull()
            painel.isVisible = aviso != null
            if (aviso == null) return@observe

            texto.text = texto(activity, aviso, avisos.size - 1)
            ciente.setOnClickListener { repositorio.marcarCiente(aviso) }
        }
    }

    fun texto(context: Context, aviso: RecusaInventario, outros: Int): String {
        val motivo = aviso.motivo?.takeIf { it.isNotBlank() } ?: context.getString(R.string.aviso_recusa_sem_motivo)
        val principal = if (aviso.inventarioInteiro) context.getString(R.string.aviso_recusa_inventario, aviso.local, motivo)
            else context.getString(R.string.aviso_recusa_etiquetas, aviso.etiquetas, aviso.local, motivo)
        return if (outros > 0) principal + " " + context.getString(R.string.aviso_recusa_outros, outros) else principal
    }
}
