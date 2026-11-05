package com.example.rfid_server.activities

import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.rfid_server.CorFaixa
import com.example.rfid_server.DetalheLeitor
import com.example.rfid_server.EstadoLeitor
import com.example.rfid_server.FaixaLeitor
import com.example.rfid_server.MensagemFaixa
import com.example.rfid_server.MotivoFalhaLeitor
import com.example.rfid_server.MotivoLeitor
import com.example.rfid_server.ParteConfiguracao
import com.example.rfid_server.R
import com.example.rfid_server.RfidManager

/**
 * Liga a faixa de layout/include_estado_leitor.xml ao estado do leitor RFID: verde quando conectado,
 * amarela conectando e vermelha desconectado (com o botão "Reconectar", que não espera a tentativa automática).
 * Desconectada, a faixa diz o motivo; conectada, avisa de falha ao começar a leitura (por alguns segundos),
 * de falha ao configurar o gatilho e de bateria baixa (regras em [FaixaLeitor]).
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

    // A falha de leitura sai da faixa sozinha depois de alguns segundos: redesenha nessa hora
    var redesenho: Runnable? = null

    fun desenhar() {
        redesenho?.let(painel::removeCallbacks)
        redesenho = null

        val estado = rfidManager.estado.value ?: EstadoLeitor.PARADO
        val detalhe = rfidManager.detalhe.value ?: DetalheLeitor()
        val agora = SystemClock.elapsedRealtime()
        val mensagem = FaixaLeitor.escolher(estado, detalhe, agora)

        texto.text = textoFaixa(mensagem, estado, detalhe)
        val desconectado = estado == EstadoLeitor.DESCONECTADO || estado == EstadoLeitor.PARADO
        botao.visibility = if (desconectado) View.VISIBLE else View.GONE

        val (fundo, cor) = when (FaixaLeitor.cor(mensagem)) {
            CorFaixa.SUCESSO -> R.color.sucesso_fundo to R.color.sucesso
            CorFaixa.ATENCAO -> R.color.atencao_fundo to R.color.atencao
            CorFaixa.ERRO -> R.color.erro_fundo to R.color.erro
        }
        painel.setBackgroundColor(ContextCompat.getColor(this, fundo))
        texto.setTextColor(ContextCompat.getColor(this, cor))
        marca.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, cor))

        FaixaLeitor.redesenharEmMs(detalhe, agora)?.let { espera ->
            val acao = Runnable { desenhar() }
            redesenho = acao
            painel.postDelayed(acao, espera.coerceAtLeast(0) + 50)
        }
    }

    rfidManager.estado.observe(this) { desenhar() }
    rfidManager.detalhe.observe(this) { desenhar() }
}

private fun Context.textoFaixa(mensagem: MensagemFaixa, estado: EstadoLeitor, detalhe: DetalheLeitor): String =
    when (mensagem) {
        MensagemFaixa.CONECTANDO, MensagemFaixa.CONECTADO, MensagemFaixa.DESCONECTADO, MensagemFaixa.PARADO -> estado.descricao
        MensagemFaixa.DESCONECTADO_MOTIVO ->
            getString(R.string.leitor_desconectado_motivo, textoMotivo(detalhe.motivoDesconexao))
        MensagemFaixa.PARADO_MOTIVO ->
            getString(R.string.leitor_parado_motivo, MotivoLeitor.minusculaInicial(textoMotivo(detalhe.motivoDesconexao)))
        MensagemFaixa.FALHA_LEITURA ->
            getString(R.string.leitor_falha_leitura, MotivoLeitor.minusculaInicial(textoMotivo(detalhe.falhaLeitura)))
        MensagemFaixa.FALHA_CONFIGURACAO -> {
            val falha = detalhe.falhaConfiguracao
            val formato = if (falha?.parte == ParteConfiguracao.POTENCIA) R.string.leitor_falha_potencia else R.string.leitor_falha_gatilho
            getString(formato, MotivoLeitor.minusculaInicial(textoMotivo(falha?.motivo)))
        }
        MensagemFaixa.BATERIA_BAIXA -> getString(R.string.leitor_bateria_baixa, detalhe.bateria ?: 0)
        MensagemFaixa.BATERIA_CRITICA -> getString(R.string.leitor_bateria_critica, detalhe.bateria ?: 0)
    }

/** Motivo traduzido; sem tradução (causa desconhecida), o texto do próprio SDK. */
private fun Context.textoMotivo(motivo: MotivoFalhaLeitor?): String {
    if (motivo == null) return getString(R.string.leitor_motivo_desconhecido)
    val recurso = MotivoLeitor.recurso(motivo.causa)
    return when {
        recurso != null -> getString(recurso)
        motivo.detalhe.isNotBlank() -> motivo.detalhe
        else -> getString(R.string.leitor_motivo_desconhecido)
    }
}
