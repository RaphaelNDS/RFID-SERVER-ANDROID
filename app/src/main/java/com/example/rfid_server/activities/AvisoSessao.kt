package com.example.rfid_server.activities

import com.example.rfid_server.erros.MascaraSensivel
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
import com.example.rfid_server.R
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
    private val aoMudarSessao: (SessaoOperador) -> Unit = {},
    /**
     * Sessão acabou (pelo tempo ou encerrada pelo servidor) com a tela aberta. Devolve true se a tela trata sozinha
     * (ex.: o Cadastro pede a senha sem sair, para não perder o formulário); false ou ausente = vai para o login.
     */
    private val aoSessaoAcabar: (() -> Boolean)? = null
) {

    private val handler = Handler(Looper.getMainLooper())
    private var dialogo: AlertDialog? = null

    private val verificacao = object : Runnable {
        override fun run() {
            verificar()
            handler.postDelayed(this, INTERVALO_MS)
        }
    }

    // Sessão encerrada pelo servidor durante a sincronização (outra thread): confere na hora, sem esperar os 30 s.
    // Sem isso o operador seguia lendo até a próxima conferência, e as leituras eram ignoradas sem aviso
    private val aoEncerrarSessao: () -> Unit = {
        handler.removeCallbacks(verificacao)
        handler.post(verificacao)
    }

    fun iniciar() {
        SessaoAtual.ouvirEncerramento(aoEncerrarSessao)
        handler.removeCallbacks(verificacao)
        handler.post(verificacao)
    }

    fun parar() {
        SessaoAtual.pararDeOuvir(aoEncerrarSessao)
        handler.removeCallbacks(verificacao)
    }

    private fun verificar() {
        if (activity.isFinishing || activity.isDestroyed) return
        // Sessão acabou com a tela aberta: volta para o login (mesma regra das telas), a não ser que a tela trate
        val sessao = SessaoAtual.get(activity) ?: run {
            if (aoSessaoAcabar?.invoke() != true) activity.exigirSessao()
            return
        }
        val agora = System.currentTimeMillis()
        val minutos = PoliticaAvisoSessao.avisoDevido(sessao.token, sessao.expiraEm, agora) ?: return

        val fim = horario(sessao.expiraEm)
        Log.i(TAG, "Sessão de ${MascaraSensivel.email(sessao.email)} termina em ${PoliticaAvisoSessao.restanteLegivel(sessao.expiraEm - agora)} (às $fim): operador avisado")

        dialogo?.dismiss()
        dialogo = AlertDialog.Builder(activity)
            .setTitle(if (minutos <= 5) R.string.sessao_acabando else R.string.sessao_termina_em_breve)
            .setMessage(activity.getString(R.string.sessao_aviso_mensagem, fim, PoliticaAvisoSessao.restanteLegivel(sessao.expiraEm - agora)))
            .setPositiveButton(R.string.sessao_renovar) { _, _ -> pedirSenha(sessao) }
            .setNegativeButton(R.string.comum_depois) { _, _ -> Log.i(TAG, "Operador ${MascaraSensivel.email(sessao.email)} adiou a renovação da sessão") }
            .show()
    }

    private fun pedirSenha(sessao: SessaoOperador, erro: String? = null) {
        val campoSenha = EditText(activity).apply {
            setHint(R.string.comum_senha)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.sessao_renovar_titulo)
            .setMessage((erro?.let { "$it\n\n" } ?: "") + activity.getString(R.string.sessao_senha_de, sessao.email))
            .setView(campoSenha)
            .setPositiveButton(R.string.sessao_renovar) { _, _ -> renovar(sessao, campoSenha.text.toString()) }
            .setNegativeButton(R.string.comum_cancelar) { _, _ -> Log.i(TAG, "Operador ${MascaraSensivel.email(sessao.email)} cancelou a renovação da sessão") }
            .show()
    }

    private fun renovar(sessao: SessaoOperador, senha: String) {
        if (senha.isEmpty()) return pedirSenha(sessao, activity.getString(R.string.sessao_informe_senha))

        Log.i(TAG, "Renovando a sessão de ${MascaraSensivel.email(sessao.email)}")
        TagService(activity).entrar(sessao.email, senha) { resposta ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                when (resposta) {
                    is RespostaServidor.Sucesso -> {
                        val nova = resposta.valor
                        AppConfig.saveSessao(activity, nova)
                        Log.i(TAG, "Sessão de ${MascaraSensivel.email(nova.email)} renovada: vale até ${horario(nova.expiraEm)}")
                        Toast.makeText(activity, activity.getString(R.string.sessao_renovada, horario(nova.expiraEm)), Toast.LENGTH_LONG).show()
                        aoMudarSessao(nova)
                    }
                    is RespostaServidor.Falha -> {
                        Log.w(TAG, "Renovação da sessão de ${MascaraSensivel.email(sessao.email)} recusada: ${resposta.mensagem}")
                        // Sessão ainda válida: deixa tentar de novo
                        if (SessaoAtual.get(activity) != null) pedirSenha(sessao, resposta.mensagem)
                    }
                    RespostaServidor.NaoEncontrado -> pedirSenha(sessao, activity.getString(R.string.comum_servidor_nao_reconheceu_login))
                }
            }
        }
    }

    companion object {
        private const val TAG = "SESSAO"
        private const val INTERVALO_MS = 30_000L

        fun horario(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale.forLanguageTag("pt-BR")).format(Date(epochMs))
    }
}
