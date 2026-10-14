package com.example.rfid_server.activities

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
            txtStatus.text.toString() in AVISOS_CONFIGURACAO -> txtStatus.visibility = View.GONE
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
        !AppConfig.servidorConfigurado(this) -> AVISO_SEM_SERVIDOR
        !AppConfig.chaveConfigurada(this) -> AVISO_SEM_CHAVE
        else -> null
    }

    private fun entrar() {
        faltaConfiguracao()?.let {
            Log.w(TAG, "Login não tentado: $it")
            mostrarErro(it)
            return
        }

        val email = edtEmail.text.toString().trim()
        val senha = edtSenha.text.toString()

        if (email.isEmpty() || senha.isEmpty()) {
            mostrarErro("Informe email e senha")
            return
        }

        btnEntrar.isEnabled = false
        txtStatus.visibility = View.GONE
        Log.i(TAG, "Tentando entrar como $email")

        TagService(this).entrar(email, senha) { resposta ->
            runOnUiThread {
                btnEntrar.isEnabled = true

                when (resposta) {
                    is RespostaServidor.Sucesso -> {
                        val sessao = resposta.valor
                        AppConfig.saveSessao(this, sessao)
                        Log.i(TAG, "Operador ${sessao.email} entrou no coletor (pode cadastrar: ${if (sessao.podeCadastrar) "sim" else "não"})")

                        if (sessao.senhaFraca) {
                            Toast.makeText(this, "Sua senha não atende mais às regras de segurança: troque-a no Sistema RFID (web).", Toast.LENGTH_LONG).show()
                        }
                        abrirPrincipal()
                    }

                    is RespostaServidor.Falha -> {
                        edtSenha.setText("")
                        mostrarErro(resposta.mensagem)
                    }

                    RespostaServidor.NaoEncontrado -> mostrarErro("Servidor não reconheceu o login")
                }
            }
        }
    }

    /** "Esqueceu a senha?": confirma o email e manda o pedido ao administrador, que gera uma senha temporária. */
    private fun esqueciSenha() {
        val campoEmail = EditText(this).apply {
            hint = "Email"
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS or android.text.InputType.TYPE_CLASS_TEXT
            setText(edtEmail.text.toString().trim())
        }

        AlertDialog.Builder(this)
            .setTitle("Esqueceu a senha?")
            .setMessage("O pedido vai para o administrador do Sistema RFID, que vai te passar uma senha temporária.")
            .setView(campoEmail)
            .setPositiveButton("Pedir nova senha") { _, _ -> pedirNovaSenha(campoEmail.text.toString().trim()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun pedirNovaSenha(email: String) {
        faltaConfiguracao()?.let {
            Log.w(TAG, "Pedido de nova senha não enviado: $it")
            mostrarErro(it)
            return
        }
        if (email.isEmpty()) {
            mostrarErro("Informe o email para pedir uma senha nova")
            return
        }

        txtStatus.visibility = View.GONE
        Log.i(TAG, "Pedindo nova senha para $email")

        TagService(this).esqueciSenha(email) { resposta ->
            runOnUiThread {
                when (resposta) {
                    is RespostaServidor.Sucesso -> AlertDialog.Builder(this)
                        .setTitle("Pedido enviado")
                        .setMessage(resposta.valor)
                        .setPositiveButton("OK", null)
                        .show()

                    is RespostaServidor.Falha -> {
                        Log.w(TAG, "Pedido de nova senha para $email não foi enviado: ${resposta.mensagem}")
                        mostrarErro("Não foi possível pedir a senha nova: ${resposta.mensagem}")
                    }

                    RespostaServidor.NaoEncontrado -> mostrarErro("Servidor não reconheceu o pedido de nova senha")
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

        private const val AVISO_SEM_SERVIDOR =
            "Coletor ainda não configurado: toque em \"Configurações do coletor\" e informe o IP do servidor e a chave do coletor"
        private const val AVISO_SEM_CHAVE =
            "Falta a chave do coletor: toque em \"Configurações do coletor\" e informe a chave fornecida pelo administrador"
        private val AVISOS_CONFIGURACAO = setOf(AVISO_SEM_SERVIDOR, AVISO_SEM_CHAVE)
    }
}
