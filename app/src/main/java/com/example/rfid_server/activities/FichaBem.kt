package com.example.rfid_server.activities

import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.rfid_server.R
import com.example.rfid_server.service.HistoricoBem
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService
import com.example.rfid_server.view.TagView
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Ficha do bem: tocar numa etiqueta cadastrada da Leitura mostra o cadastro completo (o que já está no coletor) e,
 * quando o servidor responde, onde o bem foi encontrado da última vez. Dali dá para ir direto ao Localizar.
 */
object FichaBem {

    private const val TAG = "FICHA"

    fun mostrar(activity: AppCompatActivity, tag: TagView) {
        if (!CliqueUnico.permitir()) return
        val cadastro = textoCadastro(activity, tag)

        val dialogo = AlertDialog.Builder(activity)
            .setTitle(activity.getString(R.string.ficha_titulo, tag.patrimonio.ifBlank { activity.getString(R.string.tag_sem_patrimonio) }))
            .setMessage(cadastro + "\n\n" + activity.getString(R.string.ficha_buscando))
            .setPositiveButton(R.string.ficha_localizar) { _, _ ->
                Log.i(TAG, "Operador foi localizar ${tag.epc} (patrimônio ${tag.patrimonio}) pela ficha")
                activity.startActivity(
                    Intent(activity, LocalizarActivity::class.java)
                        .putExtra(LocalizarActivity.EXTRA_EPC, tag.epc)
                        .putExtra(LocalizarActivity.EXTRA_PATRIMONIO, tag.patrimonio.ifBlank { null })
                )
            }
            .setNegativeButton(R.string.ficha_fechar, null)
            .show()

        TagService(activity).buscarHistorico(tag.epc) { resposta ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || !dialogo.isShowing) return@runOnUiThread
                val historico = when (resposta) {
                    is RespostaServidor.Sucesso -> textoHistorico(activity, resposta.valor)
                    else -> activity.getString(R.string.ficha_sem_historico)
                }
                dialogo.setMessage(cadastro + "\n\n" + historico)
            }
        }
    }

    private fun textoCadastro(activity: AppCompatActivity, tag: TagView): String = buildList {
        listOfNotNull(tag.tipo, tag.marca, tag.modelo).joinToString(" · ").takeIf { it.isNotBlank() }?.let { add(it) }
        if (tag.codigoInterno.isNotBlank()) add(activity.getString(R.string.ficha_codigo_interno, tag.codigoInterno))
        if (tag.numeroSerie.isNotBlank()) add(activity.getString(R.string.ficha_serie, tag.numeroSerie))
        add(activity.getString(R.string.ficha_etiqueta, tag.epc))
    }.joinToString("\n")

    private fun textoHistorico(activity: AppCompatActivity, h: HistoricoBem): String {
        if (h.vazio) return activity.getString(R.string.ficha_sem_historico)
        return buildList {
            // Situação no cadastro (servidor 1.6.0): baixado ou extraviado vem com aviso, para o operador não passar batido
            when (h.situacao) {
                "BAIXADO" -> add(activity.getString(R.string.ficha_aviso_baixado))
                "EXTRAVIADO" -> add(activity.getString(R.string.ficha_aviso_extraviado))
            }
            (h.situacaoRotulo ?: h.situacao)?.let { add(activity.getString(R.string.ficha_situacao, it)) }
            h.local?.let { add(activity.getString(R.string.ficha_local, it)) }
            h.unidade?.let { add(activity.getString(R.string.ficha_unidade, it)) }
            // Locação (servidor 1.12.0): alugado ou em devolução, com quem está, o pedido, até quando e para onde foi. O
            // responsável não aparece mais (pedido do usuário em 11/11: na locadora o que importa é o cliente)
            h.cliente?.let { add(activity.getString(R.string.ficha_cliente, it)) }
            h.pedido?.let { add(activity.getString(R.string.ficha_pedido, it)) }
            h.localEntrega?.let { add(activity.getString(R.string.ficha_local_entrega, it)) }
            h.previsaoDevolucao?.let { add(activity.getString(R.string.ficha_previsao_devolucao, dataCurta(it))) }
            h.ultimoLocal?.let { local ->
                add(activity.getString(R.string.ficha_ultimo_local, local, h.ultimoInventarioEm?.let(::data) ?: "—"))
            }
            h.ultimaLeituraEm?.let { em ->
                add(activity.getString(R.string.ficha_ultima_leitura, data(em), h.ultimaLeituraPor ?: "—"))
            }
        }.joinToString("\n")
    }

    // "2026-11-30" (só a data) → "30/11/2026"; outro formato vai para [data]
    private fun dataCurta(texto: String): String =
        Regex("""^(\d{4})-(\d{2})-(\d{2})$""").find(texto)?.destructured?.let { (a, m, d) -> "$d/$m/$a" } ?: data(texto)

    // ISO-8601 do servidor → "09/11/2026 10:37" no fuso do coletor; texto fora do formato aparece como veio
    private fun data(iso: String): String =
        try {
            OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        } catch (e: Exception) {
            iso
        }
}
