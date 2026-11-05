package com.example.rfid_server.activities

import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.rfid_server.AppConfig
import com.example.rfid_server.R
import com.example.rfid_server.RfidManager
import com.example.rfid_server.VersaoApp
import com.example.rfid_server.service.ResultadoConexao
import com.example.rfid_server.service.StatusConexao
import com.example.rfid_server.sincronizacao.Sincronizador
import com.example.rfid_server.service.TagService
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {

    private lateinit var edtIp: EditText
    private lateinit var edtChave: EditText
    private lateinit var layoutChave: TextInputLayout
    private lateinit var btnSalvar: Button
    private lateinit var btnTestar: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtCertificado: TextView

    private lateinit var tagService: TagService
    private lateinit var guardaPin: GuardaPin

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.barraConfiguracoes).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        edtIp = findViewById(R.id.edtIp)
        edtChave = findViewById(R.id.edtChave)
        layoutChave = findViewById(R.id.layoutChave)
        btnSalvar = findViewById(R.id.btnSalvar)
        btnTestar = findViewById(R.id.btnTestarServidor)
        txtStatus = findViewById(R.id.txtStatusServidor)
        txtCertificado = findViewById(R.id.txtCertificado)

        tagService = TagService(this)
        guardaPin = GuardaPin(this, tagService)

        edtIp.setText(AppConfig.getServerIp(this))
        // A chave salva nunca volta para a tela: quem pega o coletor não consegue copiá-la
        mostrarSituacaoChave()
        mostrarCertificadoConfirmado()
        findViewById<TextView>(R.id.txtVersao).text = getString(R.string.comum_versao_app, getString(R.string.app_name), VersaoApp.descricao())
        findViewById<Button>(R.id.btnAtualizacao).setOnClickListener {
            Log.i(TAG, "Operador pediu para procurar atualização do app")
            AtualizadorApp(this).verificar(manual = true)
        }

        findViewById<CheckBox>(R.id.chkAvisoLeitura).apply {
            isChecked = AppConfig.isAvisoLeitura(this@SettingsActivity)
            setOnCheckedChangeListener { _, ligado ->
                AppConfig.setAvisoLeitura(this@SettingsActivity, ligado)
                Log.i(TAG, "Bip e vibração a cada etiqueta nova ${if (ligado) "ligados" else "desligados"} pelo operador")
            }
        }

        configurarPotencia()

        testarServidor()

        btnTestar.setOnClickListener {
            testarServidor()
        }

        btnSalvar.setOnClickListener { salvar() }
    }

    private fun mostrarSituacaoChave() {
        layoutChave.helperText = getString(
            if (AppConfig.chaveConfigurada(this)) R.string.config_chave_ja_salva else R.string.config_chave_fornecida
        )
    }

    private fun salvar() {
        val ip = edtIp.text.toString().trim()
        val chaveDigitada = edtChave.text.toString().trim()

        if (ip.isEmpty()) {
            Toast.makeText(this, R.string.config_informe_ip, Toast.LENGTH_SHORT).show()
            return
        }
        if (!AppConfig.enderecoValido(ip)) {
            Log.w(TAG, "Configurações não salvas: endereço do servidor inválido '$ip'")
            Toast.makeText(this, R.string.config_endereco_invalido, Toast.LENGTH_LONG).show()
            return
        }

        val anterior = AppConfig.getServerIp(this)
        // Campo da chave em branco = manter a salva
        val chave = chaveDigitada.ifEmpty { AppConfig.getChaveDispositivo(this) }

        if (ip == anterior && chave == AppConfig.getChaveDispositivo(this) && AppConfig.temPin(this)) {
            Toast.makeText(this, R.string.config_nada_mudou, Toast.LENGTH_SHORT).show()
            edtChave.text = null
            Sincronizador.get(this).solicitar()
            testarServidor()
            return
        }

        guardaPin.exigir(getString(R.string.config_acao_salvar)) { gravar(ip, chave, anterior) }
    }

    private fun gravar(ip: String, chave: String, anterior: String) {
        AppConfig.saveServerIp(this, ip)
        AppConfig.saveChaveDispositivo(this, chave)
        edtChave.text = null
        mostrarSituacaoChave()

        val endereco = AppConfig.montarEndereco(ip)
        when {
            anterior.isBlank() -> Log.i(TAG, "Servidor configurado neste coletor: $endereco")
            anterior != ip -> Log.i(TAG, "Servidor do coletor alterado de ${AppConfig.montarEndereco(anterior)} para $endereco")
            else -> Log.i(TAG, "Configurações salvas: servidor $endereco")
        }
        if (chave.isEmpty()) {
            Log.w(TAG, "Configurações salvas sem a chave do coletor: o servidor vai recusar as chamadas")
            Toast.makeText(this, R.string.config_salvo_sem_chave, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.config_salvas, Toast.LENGTH_SHORT).show()
        }

        // Leituras guardadas por falta de conexão ou chave errada podem ir agora
        Sincronizador.get(this).solicitar()

        testarServidor()
    }

    /** Potência do leitor (% da máxima). Muda na hora, sem o botão Salvar e sem PIN, como o bip. */
    private fun configurarPotencia() {
        val spinner = findViewById<Spinner>(R.id.spnPotencia)
        val atual = AppConfig.getPotenciaLeitor(this)
        // Valor fora da lista (não acontece pela tela): mostra o mais próximo
        val selecionado = POTENCIAS.indices.minByOrNull { kotlin.math.abs(POTENCIAS[it].second - atual) } ?: 0

        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, POTENCIAS.map { getString(it.first) })
        spinner.setSelection(selecionado, false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val percentual = POTENCIAS[position].second
                if (percentual == AppConfig.getPotenciaLeitor(this@SettingsActivity)) return
                AppConfig.setPotenciaLeitor(this@SettingsActivity, percentual)
                RfidManager.get(this@SettingsActivity).aplicarPotencia()
                Log.i(TAG, "Potência do leitor mudada pelo operador para ${getString(POTENCIAS[position].first)}")
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun testarServidor() {
        // Coletor novo: não há servidor para testar (antes testava o IP de desenvolvimento que ficava no código)
        if (!AppConfig.servidorConfigurado(this)) {
            Log.i(TAG, "Coletor sem servidor configurado: aguardando o IP e a chave")
            txtStatus.setText(R.string.config_status_nao_configurado)
            txtStatus.setTextColor(ContextCompat.getColor(this, R.color.atencao))
            return
        }

        txtStatus.setText(R.string.config_status_testando)
        txtStatus.setTextColor(ContextCompat.getColor(this, R.color.texto_secundario))

        tagService.testarConexao { resultado ->
            runOnUiThread {
                // Saiu da tela enquanto o servidor respondia: o diálogo do certificado não pode abrir numa tela fechada
                if (isFinishing || isDestroyed) return@runOnUiThread
                mostrarResultado(resultado)
            }
        }
    }

    private fun mostrarResultado(resultado: ResultadoConexao) {
        if (resultado.status == StatusConexao.CONECTADO) {
            txtStatus.setText(R.string.config_status_conectado)
            txtStatus.setTextColor(ContextCompat.getColor(this, R.color.sucesso))
            return
        }

        txtStatus.text = getString(R.string.config_status_falha, resultado.status.descricao)
        txtStatus.setTextColor(ContextCompat.getColor(this, R.color.erro))

        val impressao = resultado.impressaoDigital ?: return
        when (resultado.status) {
            StatusConexao.CERTIFICADO_NAO_CONFIRMADO -> perguntarConfianca(impressao, anterior = null)
            StatusConexao.CERTIFICADO_DIFERENTE -> perguntarConfianca(impressao, anterior = AppConfig.getImpressaoDigital(this))
            else -> Unit
        }
    }

    /**
     * Mostra a impressão digital do certificado que o servidor apresentou para o responsável comparar com a
     * tela Status do servidor (web) ou com o log. Só depois de confirmada o coletor passa a conversar com ele.
     */
    private fun perguntarConfianca(impressao: String, anterior: String?) {
        val mensagem = buildString {
            if (anterior == null) {
                append(getString(R.string.config_cert_apresentado)).append("\n\n")
            } else {
                append(getString(R.string.config_cert_diferente)).append("\n\n")
                append(getString(R.string.config_cert_motivo)).append("\n\n")
                append(getString(R.string.config_cert_novo)).append("\n")
            }
            append(formatar(impressao))
            if (anterior != null) append("\n\n").append(getString(R.string.config_cert_anterior)).append("\n").append(formatar(anterior))
            append("\n\n").append(getString(R.string.config_cert_compare))
        }

        val dialogo = AlertDialog.Builder(this)
            .setTitle(if (anterior == null) R.string.config_cert_confirmar_titulo else R.string.config_cert_mudou_titulo)
            .setMessage(mensagem)
            .setPositiveButton(R.string.config_cert_confiar) { _, _ ->
                // Certificado trocado exige o PIN (é exatamente o que um impostor na rede pediria para aceitar);
                // o primeiro, na configuração inicial, só se o PIN já existir
                if (anterior == null) guardaPin.exigirSeTiver(getString(R.string.config_acao_confiar)) { confiar(impressao, anterior) }
                else guardaPin.exigir(getString(R.string.config_acao_confiar_novo)) { confiar(impressao, anterior) }
            }
            .setNegativeButton(R.string.config_cert_nao_confiar) { _, _ ->
                Log.w(TAG, "Certificado do servidor NÃO confirmado pelo responsável: $impressao")
            }
            .show()

        dialogo.findViewById<TextView>(android.R.id.message)?.typeface = Typeface.MONOSPACE
    }

    private fun confiar(impressao: String, anterior: String?) {
        AppConfig.saveImpressaoDigital(this, impressao)
        Log.i(TAG, (if (anterior == null) "Certificado do servidor confirmado" else "Novo certificado do servidor confirmado (antes: $anterior)") + ": $impressao")
        mostrarCertificadoConfirmado()
        Sincronizador.get(this).solicitar()
        testarServidor()
    }

    private fun mostrarCertificadoConfirmado() {
        val impressao = AppConfig.getImpressaoDigital(this)
        txtCertificado.text = if (impressao == null) getString(R.string.config_cert_nao_confirmado)
        else getString(R.string.config_cert_confirmado, formatar(impressao))
    }

    /** Quebra a impressão digital (32 bytes) em 4 linhas de 8, mais fácil de comparar a olho. */
    private fun formatar(impressao: String): String =
        impressao.split(':').chunked(8).joinToString("\n") { it.joinToString(":") }

    companion object {
        private const val TAG = "SETTINGS"

        /** Opções da potência do leitor: texto na tela (recurso) e % da máxima. */
        private val POTENCIAS = listOf(
            R.string.config_potencia_maxima to 100,
            R.string.config_potencia_alta to 75,
            R.string.config_potencia_media to 50,
            R.string.config_potencia_baixa to 25
        )
    }
}
