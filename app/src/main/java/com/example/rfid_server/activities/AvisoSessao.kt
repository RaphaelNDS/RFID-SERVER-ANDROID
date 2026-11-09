package com.example.rfid_server.activities

import com.example.rfid_server.erros.MascaraSensivel
import android.os.Handler
import android.os.Looper
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

    private var dialogo: AlertDialog? = null

    // Confere a cada 30 s e também logo depois do fim exato da sessão: antes, por até 30 s depois de vencer, o gatilho
    // era ignorado sem aviso (as leituras sem operador são descartadas) e o Iniciar do inventário dizia "em andamento"
    private val laco = LacoConferencia(Handler(Looper.getMainLooper()), INTERVALO_MS, proximaEm = {
        PoliticaAvisoSessao.esperaAteConferir(AppConfig.getSessao(activity)?.expiraEm, System.currentTimeMillis(), INTERVALO_MS)
    }) { verificar() }

    // Sessão encerrada pelo servidor durante a sincronização (outra thread): confere na hora, sem esperar os 30 s.
    // Sem isso o operador seguia lendo até a próxima conferência, e as leituras eram ignoradas sem aviso
    private val aoEncerrarSessao: () -> Unit = { laco.conferirJa() }

    fun iniciar() {
        SessaoAtual.ouvirEncerramento(aoEncerrarSessao)
        laco.iniciar()
    }

    fun parar() {
        SessaoAtual.pararDeOuvir(aoEncerrarSessao)
        laco.parar()
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
        // Campo com margem do diálogo, foco e teclado já abertos; o Concluir do teclado renova
        val vista = activity.layoutInflater.inflate(R.layout.dialogo_senha, null)
        val campoSenha = vista.findViewById<EditText>(R.id.edtCampoDialogo)

        val pedido = AlertDialog.Builder(activity)
            .setTitle(R.string.sessao_renovar_titulo)
            .setMessage((erro?.let { "$it\n\n" } ?: "") + activity.getString(R.string.sessao_senha_de, sessao.email))
            .setView(vista)
            .setPositiveButton(R.string.sessao_renovar) { _, _ -> renovar(sessao, campoSenha.text.toString()) }
            .setNegativeButton(R.string.comum_cancelar) { _, _ -> Log.i(TAG, "Operador ${MascaraSensivel.email(sessao.email)} cancelou a renovação da sessão") }
            .create()
        CampoDialogo.mostrar(pedido, campoSenha)
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

    /**
     * Conferência repetida a cada [intervaloMs] na thread do [handler], ligada no [iniciar] e desligada no [parar].
     * Só reagenda se ainda estiver ligada depois de conferir: um [parar] chamado DENTRO da conferência (ex.: o Cadastro,
     * no aoSessaoAcabar, para o aviso e pede a senha) não é desfeito. Antes o reagendamento vinha sempre, e o pedido de
     * senha do Cadastro voltava a cada 30 s mesmo depois de Cancelar.
     */
    internal class LacoConferencia(
        private val handler: Handler,
        private val intervaloMs: Long,
        /** Espera até a próxima conferência (ex.: menos que [intervaloMs] quando a sessão vence antes). */
        private val proximaEm: () -> Long = { intervaloMs },
        private val conferir: () -> Unit
    ) {
        @Volatile
        var ativo = false
            private set

        private val passo = object : Runnable {
            override fun run() {
                // Pedido do conferirJa (outra thread) que chegou depois do parar(): a tela já não acompanha a sessão
                if (!ativo) return
                conferir()
                if (ativo) handler.postDelayed(this, proximaEm())
            }
        }

        fun iniciar() {
            ativo = true
            handler.removeCallbacks(passo)
            handler.post(passo)
        }

        fun parar() {
            ativo = false
            handler.removeCallbacks(passo)
        }

        /** Confere agora (pode chamar de qualquer thread); o laço segue a cada [intervaloMs] a partir daí. */
        fun conferirJa() {
            handler.removeCallbacks(passo)
            handler.post(passo)
        }
    }

    companion object {
        private const val TAG = "SESSAO"
        private const val INTERVALO_MS = 30_000L

        fun horario(epochMs: Long): String = SimpleDateFormat("HH:mm", Locale.forLanguageTag("pt-BR")).format(Date(epochMs))
    }
}
