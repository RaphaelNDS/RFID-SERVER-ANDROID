package com.example.rfid_server.telas

import android.content.Context
import android.content.res.ColorStateList
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.rfid_server.configuracoes.AppConfig
import com.example.rfid_server.leitor.CorFaixa
import com.example.rfid_server.leitor.FiltroLeitura
import com.example.rfid_server.leitor.DetalheLeitor
import com.example.rfid_server.leitor.EstadoLeitor
import com.example.rfid_server.leitor.FaixaLeitor
import com.example.rfid_server.leitor.MensagemFaixa
import com.example.rfid_server.leitor.MotivoFalhaLeitor
import com.example.rfid_server.leitor.MotivoLeitor
import com.example.rfid_server.leitor.ParteConfiguracao
import com.example.rfid_server.R
import com.example.rfid_server.leitor.RfidManager

/**
 * Cores da faixa do leitor para cada situação (lógica pura, testável sem Android).
 *  - Conectado: discreta, fundo verde-claro com texto e bolinha verdes.
 *  - Atenção (conectando, bateria baixa, falha de configuração): fundo SÓLIDO âmbar com texto branco (~6,4:1).
 *  - Erro (desconectado, parado, falha de leitura, bateria crítica): fundo SÓLIDO vermelho com texto branco (~5,6:1).
 * No fundo sólido a bolinha é branca (na cor do fundo ela sumiria).
 */
object EstiloFaixaLeitor {

    data class Estilo(@ColorRes val fundo: Int, @ColorRes val texto: Int, @ColorRes val marca: Int, val solido: Boolean)

    fun de(cor: CorFaixa): Estilo = when (cor) {
        CorFaixa.SUCESSO -> Estilo(R.color.sucesso_fundo, R.color.sucesso, R.color.sucesso, solido = false)
        CorFaixa.ATENCAO -> Estilo(R.color.atencao, R.color.white, R.color.white, solido = true)
        CorFaixa.ERRO -> Estilo(R.color.erro, R.color.white, R.color.white, solido = true)
    }
}

/**
 * Liga a faixa de layout/include_estado_leitor.xml ao estado do leitor RFID: discreta (verde-claro) quando conectado,
 * âmbar sólida conectando e vermelha sólida desconectado (com o botão "Reconectar", que não espera a tentativa automática).
 * Desconectada, a faixa diz o motivo; conectada, avisa de falha ao começar a leitura (por alguns segundos),
 * de falha ao configurar o gatilho e de bateria baixa (regras em [FaixaLeitor]).
 * Chame no onCreate de uma tela que tenha o include.
 */
fun AppCompatActivity.mostrarEstadoLeitor(
    rfidManager: RfidManager,
    /**
     * Mostra na faixa "conectado" os filtros da leitura que estão ligados (alcance; "só da empresa" só onde vale, no
     * Inventário): o operador vê por que a sala vizinha ou uma etiqueta antiga não aparece. O Localizar não usa filtros.
     */
    mostrarFiltros: Boolean = false,
    usaFiltroEmpresa: Boolean = false
) {
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

        val filtros = if (mostrarFiltros && mensagem == MensagemFaixa.CONECTADO) textoFiltros(usaFiltroEmpresa) else null
        texto.text = textoFaixa(mensagem, estado, detalhe).let { if (filtros != null) "$it · $filtros" else it }
        val desconectado = estado == EstadoLeitor.DESCONECTADO || estado == EstadoLeitor.PARADO
        botao.visibility = if (desconectado) View.VISIBLE else View.GONE

        val estilo = EstiloFaixaLeitor.de(FaixaLeitor.cor(mensagem))
        painel.setBackgroundColor(ContextCompat.getColor(this, estilo.fundo))
        texto.setTextColor(ContextCompat.getColor(this, estilo.texto))
        marca.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, estilo.marca))

        FaixaLeitor.redesenharEmMs(detalhe, agora)?.let { espera ->
            val acao = Runnable { desenhar() }
            redesenho = acao
            painel.postDelayed(acao, espera.coerceAtLeast(0) + 50)
        }
    }

    rfidManager.estado.observe(this) { desenhar() }
    rfidManager.detalhe.observe(this) { desenhar() }
    // Volta das Configurações com outro filtro: a faixa atualiza sem esperar o leitor mudar de estado
    lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
        override fun onResume(owner: androidx.lifecycle.LifecycleOwner) = desenhar()
    })
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

/** "alcance curto", "só da empresa", "alcance médio · só da empresa"; null sem filtro ligado. */
private fun Context.textoFiltros(usaFiltroEmpresa: Boolean): String? {
    val partes = buildList {
        when (AppConfig.getRssiMinimo(this@textoFiltros)) {
            null -> Unit
            FiltroLeitura.ALCANCES.last() -> add(getString(R.string.leitor_filtro_curto))
            else -> add(getString(R.string.leitor_filtro_medio))
        }
        if (usaFiltroEmpresa && AppConfig.isSoEtiquetasEmpresa(this@textoFiltros)) add(getString(R.string.leitor_filtro_empresa))
    }
    return partes.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
