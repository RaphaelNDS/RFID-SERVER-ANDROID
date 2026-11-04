package com.example.rfid_server.activities

import com.example.rfid_server.erros.MascaraSensivel
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.rfid_server.AppConfig
import com.example.rfid_server.R
import com.example.rfid_server.SessaoAtual
import com.example.rfid_server.VersaoApp
import com.example.rfid_server.erros.RegistroErros
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService

/**
 * Tela inicial do app: o operador entra com o usuário do Sistema RFID (o mesmo da web).
 * O login exige rede (a senha é conferida no servidor); depois, a sessão vale 12h, inclusive offline.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var edtEmail: EditText
    private lateinit var edtSenha: EditText
    private lateinit var btnEntrar: Button
    private lateinit var txtStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Sessão ainda válida (ex.: app reaberto no meio do turno): vai direto para a leitura
        if (SessaoAtual.get(this) != null) {
            abrirPrincipal()
            return
        }

        setContentView(R.layout.activity_login)

        edtEmail = findViewById(R.id.edtEmail)
        edtSenha = findViewById(R.id.edtSenha)
        btnEntrar = findViewById(R.id.btnEntrar)
        txtStatus = findViewById(R.id.txtStatus)
        findViewById<TextView>(R.id.txtVersao).text = VersaoApp.descricao()

        // Primeira linha útil do log antes do login: qual versão está neste coletor
        if (savedInstanceState == null) Log.i(TAG, "${getString(R.string.app_name)} aberto na tela de login: ${VersaoApp.descricao()}")

        edtEmail.setText(AppConfig.getUltimoEmail(this))
        if (edtEmail.text.isNotEmpty()) edtSenha.requestFocus()

        intent.getStringExtra(EXTRA_MOTIVO)?.let { mostrarErro(it) }

        btnEntrar.setOnClickListener { entrar() }
        edtSenha.setOnEditorActionListener { _, acao, _ ->
            if (acao == EditorInfo.IME_ACTION_DONE) { entrar(); true } else false
        }
        findViewById<Button>(R.id.btnEsqueciSenha).setOnClickListener { esqueciSenha() }
        findViewById<Button>(R.id.btnConfiguracoes).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    // Também ao voltar das Configurações: o aviso some assim que o coletor fica configurado
    override fun onResume() {
        super.onResume()
        if (!::txtStatus.isInitialized) return   // sessão válida: a tela já foi para a leitura

        val falta = faltaConfiguracao()
        when {
            falta != null -> {
                Log.w(TAG, "Coletor sem configuração completa: $falta")
                mostrarErro(falta)
            }
            txtStatus.text.toString() in avisosConfiguracao() -> txtStatus.visibility = View.GONE
        }

        if (falta == null) {
            // Início do turno: oferece a versão nova do app, se o servidor publicou uma
            AtualizadorApp(this).verificar(manual = false)
            // Erros guardados (ex.: o app travou antes do login): vão para o servidor sem esperar a sincronização
            val app = applicationContext
            Thread { RegistroErros.enviarPendentes(app) }.start()
        }
    }

    /** O que falta configurar para o coletor conseguir falar com o servidor, ou null se está tudo certo. */
    private fun faltaConfiguracao(): String? = when {
        !AppConfig.servidorConfigurado(this) -> getString(R.string.login_aviso_sem_servidor)
        !AppConfig.chaveConfigurada(this) -> getString(R.string.login_aviso_sem_chave)
        else -> null
    }

    /** Avisos de configuração: somem da tela assim que o coletor fica configurado (ver onResume). */
    private fun avisosConfiguracao() = setOf(getString(R.string.login_aviso_sem_servidor), getString(R.string.login_aviso_sem_chave))

    private fun entrar() {
        faltaConfiguracao()?.let {
            Log.w(TAG, "Login não tentado: $it")
            mostrarErro(it)
            return
        }

        val email = edtEmail.text.toString().trim()
        val senha = edtSenha.text.toString()

        if (email.isEmpty() || senha.isEmpty()) {
            mostrarErro(getString(R.string.login_informe_email_senha))
            return
        }

        btnEntrar.isEnabled = false
        txtStatus.visibility = View.GONE
        Log.i(TAG, "Tentando entrar como ${MascaraSensivel.email(email)}")

        TagService(this).entrar(email, senha) { resposta ->
            runOnUiThread {
                btnEntrar.isEnabled = true

                when (resposta) {
                    is RespostaServidor.Sucesso -> {
                        val sessao = resposta.valor
                        AppConfig.saveSessao(this, sessao)
                        Log.i(TAG, "Operador ${MascaraSensivel.email(sessao.email)} entrou no coletor (pode cadastrar: ${if (sessao.podeCadastrar) "sim" else "não"})")

                        if (sessao.senhaFraca) {
                            Toast.makeText(this, R.string.login_senha_fraca, Toast.LENGTH_LONG).show()
                        }
                        abrirPrincipal()
                    }

                    is RespostaServidor.Falha -> {
                        edtSenha.setText("")
                        mostrarErro(resposta.mensagem)
                    }

                    RespostaServidor.NaoEncontrado -> mostrarErro(getString(R.string.comum_servidor_nao_reconheceu_login))
                }
            }
        }
    }

    /** "Esqueceu a senha?": confirma o email e manda o pedido ao administrador, que gera uma senha temporária. */
    private fun esqueciSenha() {
        val campoEmail = EditText(this).apply {
            setHint(R.string.comum_email)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or android.text.InputType.TYPE_CLASS_TEXT
            setText(edtEmail.text.toString().trim())
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.login_esqueceu_senha)
            .setMessage(R.string.login_esqueci_mensagem)
            .setView(campoEmail)
            .setPositiveButton(R.string.login_pedir_nova_senha) { _, _ -> pedirNovaSenha(campoEmail.text.toString().trim()) }
            .setNegativeButton(R.string.comum_cancelar, null)
            .show()
    }

    private fun pedirNovaSenha(email: String) {
        faltaConfiguracao()?.let {
            Log.w(TAG, "Pedido de nova senha não enviado: $it")
            mostrarErro(it)
            return
        }
        if (email.isEmpty()) {
            mostrarErro(getString(R.string.login_informe_email_nova_senha))
            return
        }

        txtStatus.visibility = View.GONE
        Log.i(TAG, "Pedindo nova senha para ${MascaraSensivel.email(email)}")

        TagService(this).esqueciSenha(email) { resposta ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (resposta) {
                    is RespostaServidor.Sucesso -> AlertDialog.Builder(this)
                        .setTitle(R.string.login_pedido_enviado)
                        .setMessage(resposta.valor)
                        .setPositiveButton(R.string.comum_ok, null)
                        .show()

                    is RespostaServidor.Falha -> {
                        Log.w(TAG, "Pedido de nova senha para ${MascaraSensivel.email(email)} não foi enviado: ${resposta.mensagem}")
                        mostrarErro(getString(R.string.login_falha_nova_senha, resposta.mensagem))
                    }

                    RespostaServidor.NaoEncontrado -> mostrarErro(getString(R.string.login_pedido_nao_reconhecido))
                }
            }
        }
    }

    private fun mostrarErro(mensagem: String) {
        txtStatus.text = mensagem
        txtStatus.visibility = View.VISIBLE
    }

    private fun abrirPrincipal() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val TAG = "LOGIN"
        const val EXTRA_MOTIVO = "motivo"
    }
}
