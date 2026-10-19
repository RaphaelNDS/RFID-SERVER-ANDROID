package com.example.rfid_server.activities

import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.rfid_server.AppConfig
import com.example.rfid_server.PoliticaAvisoSessao
import com.example.rfid_server.SessaoAtual
import com.example.rfid_server.SessaoOperador
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Avisa o operador antes de a sessão (12h) acabar e deixa renovar sem sair da tela: a renovação é um login de
 * novo com a senha (o email já vem preenchido). O que estiver na tela e na fila continua: cada leitura guardada
 * vai para o servidor com a sessão em que foi feita.
 *
 * Use nas telas de trabalho: [iniciar] no onResume e [parar] no onPause. [aoMudarSessao] é chamado depois de
 * renovar (ex.: atualizar "até 18:30" na tela).
 */
class AvisoSessao(
    private val activity: AppCompatActivity,
    private val aoMudarSessao: (SessaoOperador) -> Unit = {}
) {

    private val handler = Handler(Looper.getMainLooper())
    private var dialogo: AlertDialog? = null

    private val verificacao = object : Runnable {
        override fun run() {
            verificar()
            handler.postDelayed(this, INTERVALO_MS)
        }
    }

    fun iniciar() {
        handler.removeCallbacks(verificacao)
        handler.post(verificacao)
    }

    fun parar() {
        handler.removeCallbacks(verificacao)
    }

    private fun verificar() {
        if (activity.isFinishing || activity.isDestroyed) return
        // Sessão acabou com a tela aberta: volta para o login (mesma regra das telas)
        val sessao = activity.exigirSessao() ?: return
        val agora = System.currentTimeMillis()
        val minutos = PoliticaAvisoSessao.avisoDevido(sessao.token, sessao.expiraEm, agora) ?: return

        val fim = horario(sessao.expiraEm)
        Log.i(TAG, "Sessão de ${sessao.email} termina em ${PoliticaAvisoSessao.restanteLegivel(sessao.expiraEm - agora)} (às $fim): operador avisado")

        dialogo?.dismiss()
        dialogo = AlertDialog.Builder(activity)
            .setTitle(if (minutos <= 5) "Sua sessão está acabando" else "Sessão termina em breve")
            .setMessage("Sua sessão termina às $fim (em ${PoliticaAvisoSessao.restanteLegivel(sessao.expiraEm - agora)}). " +
                "Depois disso o app volta para a tela de login.\n\nRenove agora com a sua senha: o que está na tela e o que " +
                "ainda não foi enviado continuam.")
            .setPositiveButton("Renovar") { _, _ -> pedirSenha(sessao) }
            .setNegativeButton("Depois") { _, _ -> Log.i(TAG, "Operador ${sessao.email} adiou a renovação da sessão") }
            .show()
    }

    private fun pedirSenha(sessao: SessaoOperador, erro: String? = null) {
        val campoSenha = EditText(activity).apply {
            hint = "Senha"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(activity)
            .setTitle("Renovar sessão")
            .setMessage((erro?.let { "$it\n\n" } ?: "") + "Senha de ${sessao.email}:")
            .setView(campoSenha)
            .setPositiveButton("Renovar") { _, _ -> renovar(sessao, campoSenha.text.toString()) }
            .setNegativeButton("Cancelar") { _, _ -> Log.i(TAG, "Operador ${sessao.email} cancelou a renovação da sessão") }
            .show()
    }

    private fun renovar(sessao: SessaoOperador, senha: String) {
        if (senha.isEmpty()) return pedirSenha(sessao, "Informe a senha")

        Log.i(TAG, "Renovando a sessão de ${sessao.email}")
        TagService(activity).entrar(sessao.email, senha) { resposta ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when (resposta) {
                    is RespostaServidor.Sucesso -> {
                        val nova = resposta.valor
                        AppConfig.saveSessao(activity, nova)
                        Log.i(TAG, "Sessão de ${nova.email} renovada: vale até ${horario(nova.expiraEm)}")
                        Toast.makeText(activity, "Sessão renovada até ${horario(nova.expiraEm)}", Toast.LENGTH_LONG).show()
                        aoMudarSessao(nova)
                    }
                    is RespostaServidor.Falha -> {
                        Log.w(TAG, "Renovação da sessão de ${sessao.email} recusada: ${resposta.mensagem}")
                        // Sessão ainda válida: deixa tentar de novo
                        if (SessaoAtual.get(activity) != null) pedirSenha(sessao, resposta.mensagem)
                    }
                    RespostaServidor.NaoEncontrado -> pedirSenha(sessao, "Servidor não reconheceu o login")
                }
            }
        }
    }

    companion object {
        private const val TAG = "SESSAO"
        private const val INTERVALO_MS = 30_000L

        fun horario(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale("pt", "BR")).format(Date(epochMs))
    }
}
