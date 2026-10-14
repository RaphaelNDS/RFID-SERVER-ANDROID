package com.example.rfid_server.activities

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.rfid_server.AppConfig
import com.example.rfid_server.R
import com.example.rfid_server.VersaoApp
import com.example.rfid_server.service.ResultadoConexao
import com.example.rfid_server.service.StatusConexao
import com.example.rfid_server.sincronizacao.Sincronizador
import com.example.rfid_server.service.TagService

class SettingsActivity : AppCompatActivity() {

    private lateinit var edtIp: EditText
    private lateinit var edtChave: EditText
    private lateinit var btnSalvar: Button
    private lateinit var btnTestar: Button
    private lateinit var txtStatus: TextView
    private lateinit var txtCertificado: TextView

    private lateinit var tagService: TagService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        edtIp = findViewById(R.id.edtIp)
        edtChave = findViewById(R.id.edtChave)
        btnSalvar = findViewById(R.id.btnSalvar)
        btnTestar = findViewById(R.id.btnTestarServidor)
        txtStatus = findViewById(R.id.txtStatusServidor)
        txtCertificado = findViewById(R.id.txtCertificado)

        tagService = TagService(this)

        edtIp.setText(AppConfig.getServerIp(this))
        edtChave.setText(AppConfig.getChaveDispositivo(this))
        mostrarCertificadoConfirmado()
        findViewById<TextView>(R.id.txtVersao).text = "${getString(R.string.app_name)} · ${VersaoApp.descricao()}"

        findViewById<CheckBox>(R.id.chkAvisoLeitura).apply {
            isChecked = AppConfig.isAvisoLeitura(this@SettingsActivity)
            setOnCheckedChangeListener { _, ligado ->
                AppConfig.setAvisoLeitura(this@SettingsActivity, ligado)
                Log.i(TAG, "Bip e vibração a cada etiqueta nova ${if (ligado) "ligados" else "desligados"} pelo operador")
            }
        }

        testarServidor()

        btnTestar.setOnClickListener {
            testarServidor()
        }

        btnSalvar.setOnClickListener { salvar() }
    }

    private fun salvar() {
        val ip = edtIp.text.toString().trim()
        val chave = edtChave.text.toString().trim()

        if (ip.isEmpty()) {
            Toast.makeText(this, "Informe o IP do servidor", Toast.LENGTH_SHORT).show()
            return
        }
        if (!AppConfig.enderecoValido(ip)) {
            Log.w(TAG, "Configurações não salvas: endereço do servidor inválido '$ip'")
            Toast.makeText(this, "Endereço inválido. Use o IP (ex.: 192.168.0.10) ou IP:porta (ex.: 192.168.0.10:8443)", Toast.LENGTH_LONG).show()
            return
        }

        val anterior = AppConfig.getServerIp(this)
        AppConfig.saveServerIp(this, ip)
        AppConfig.saveChaveDispositivo(this, chave)

        val endereco = AppConfig.montarEndereco(ip)
        when {
            anterior.isBlank() -> Log.i(TAG, "Servidor configurado neste coletor: $endereco")
            anterior != ip -> Log.i(TAG, "Servidor do coletor alterado de ${AppConfig.montarEndereco(anterior)} para $endereco")
            else -> Log.i(TAG, "Configurações salvas: servidor $endereco")
        }
        if (chave.isEmpty()) {
            Log.w(TAG, "Configurações salvas sem a chave do coletor: o servidor vai recusar as chamadas")
            Toast.makeText(this, "Salvo, mas sem a chave do coletor o servidor recusa as chamadas", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Configurações salvas!", Toast.LENGTH_SHORT).show()
        }

        // Leituras guardadas por falta de conexão ou chave errada podem ir agora
        Sincronizador.get(this).solicitar()

        testarServidor()
    }

    private fun testarServidor() {
        // Coletor novo: não há servidor para testar (antes testava o IP de desenvolvimento que ficava no código)
        if (!AppConfig.servidorConfigurado(this)) {
            Log.i(TAG, "Coletor sem servidor configurado: aguardando o IP e a chave")
            txtStatus.text = "Servidor: não configurado. Informe o IP do servidor e a chave do coletor e toque em Salvar"
            txtStatus.setTextColor(Color.parseColor("#E65100"))
            return
        }

        txtStatus.text = "Servidor: testando..."
        txtStatus.setTextColor(Color.GRAY)

        tagService.testarConexao { resultado ->
            runOnUiThread { mostrarResultado(resultado) }
        }
    }

    private fun mostrarResultado(resultado: ResultadoConexao) {
        if (resultado.status == StatusConexao.CONECTADO) {
            txtStatus.text = "Servidor: conectado com segurança (HTTPS) ✅"
            txtStatus.setTextColor(Color.parseColor("#2E7D32"))
            return
        }

        txtStatus.text = "Servidor: ${resultado.status.descricao} ❌"
        txtStatus.setTextColor(Color.parseColor("#C62828"))

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
                append("O servidor apresentou este certificado:\n\n")
            } else {
                append("ATENÇÃO: o certificado do servidor é DIFERENTE do confirmado antes.\n\n")
                append("Isso acontece se o certificado foi trocado de propósito — ou se alguém está se passando pelo servidor na rede.\n\n")
                append("Novo:\n")
            }
            append(formatar(impressao))
            if (anterior != null) append("\n\nAnterior:\n").append(formatar(anterior))
            append("\n\nCompare com a impressão digital da tela Status do Sistema RFID (web). Confirme só se for IGUAL.")
        }

        val dialogo = AlertDialog.Builder(this)
            .setTitle(if (anterior == null) "Confirmar o servidor" else "Certificado do servidor mudou")
            .setMessage(mensagem)
            .setPositiveButton("É igual, confiar") { _, _ ->
                AppConfig.saveImpressaoDigital(this, impressao)
                Log.i(TAG, (if (anterior == null) "Certificado do servidor confirmado" else "Novo certificado do servidor confirmado (antes: $anterior)") + ": $impressao")
                mostrarCertificadoConfirmado()
                Sincronizador.get(this).solicitar()
                testarServidor()
            }
            .setNegativeButton("Não confiar") { _, _ ->
                Log.w(TAG, "Certificado do servidor NÃO confirmado pelo responsável: $impressao")
            }
            .show()

        dialogo.findViewById<TextView>(android.R.id.message)?.typeface = Typeface.MONOSPACE
    }

    private fun mostrarCertificadoConfirmado() {
        val impressao = AppConfig.getImpressaoDigital(this)
        txtCertificado.text = if (impressao == null) "Certificado do servidor: ainda não confirmado"
        else "Certificado confirmado (SHA-256):\n${formatar(impressao)}"
    }

    /** Quebra a impressão digital (32 bytes) em 4 linhas de 8, mais fácil de comparar a olho. */
    private fun formatar(impressao: String): String =
        impressao.split(':').chunked(8).joinToString("\n") { it.joinToString(":") }

    companion object {
        private const val TAG = "SETTINGS"
    }
}
