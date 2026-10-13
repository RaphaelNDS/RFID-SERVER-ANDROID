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
import androidx.appcompat.app.AppCompatActivity
import com.example.rfid_server.AppConfig
import com.example.rfid_server.R
import com.example.rfid_server.SessaoAtual
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

        edtEmail.setText(AppConfig.getUltimoEmail(this))
        if (edtEmail.text.isNotEmpty()) edtSenha.requestFocus()

        intent.getStringExtra(EXTRA_MOTIVO)?.let { mostrarErro(it) }

        btnEntrar.setOnClickListener { entrar() }
        edtSenha.setOnEditorActionListener { _, acao, _ ->
            if (acao == EditorInfo.IME_ACTION_DONE) { entrar(); true } else false
        }
        findViewById<Button>(R.id.btnConfiguracoes).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun entrar() {
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
