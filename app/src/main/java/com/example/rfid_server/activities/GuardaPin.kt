package com.example.rfid_server.activities

import com.example.rfid_server.erros.MascaraSensivel
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.rfid_server.AppConfig
import com.example.rfid_server.PinColetor
import com.example.rfid_server.R
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Diálogos do PIN das Configurações (PinColetor): pedir o PIN antes de trocar o servidor, a chave ou o
 * certificado; criar o PIN na primeira vez; e "Esqueci o PIN", que um ADMIN libera pelo servidor.
 */
class GuardaPin(private val activity: AppCompatActivity, private val tagService: TagService) {

    /** Pede o PIN e chama [liberado] se estiver certo. Coletor ainda sem PIN: cria um antes (é obrigatório). */
    fun exigir(acao: String, liberado: () -> Unit) {
        if (AppConfig.temPin(activity)) pedir(acao, liberado) else criar(liberado)
    }

    /** Pede o PIN só se já existir um (ex.: confirmar o certificado na primeira configuração, antes de salvar). */
    fun exigirSeTiver(acao: String, liberado: () -> Unit) {
        if (AppConfig.temPin(activity)) pedir(acao, liberado) else liberado()
    }

    private fun pedir(acao: String, liberado: () -> Unit) {
        // Relógio de parede e tempo desde a ligação: adiantar o relógio do Android não acaba com o bloqueio
        val restante = PinColetor.restanteBloqueio(
            AppConfig.getTentativasPin(activity), System.currentTimeMillis(), SystemClock.elapsedRealtime()
        )
        if (restante > 0) {
            val minutos = (restante - 1) / 60_000 + 1
            Log.w(TAG, "PIN das Configurações bloqueado: faltam $minutos minuto(s)")
            AlertDialog.Builder(activity)
                .setTitle(R.string.pin_bloqueado_titulo)
                .setMessage(activity.getString(R.string.pin_bloqueado_mensagem, minutos))
                .setPositiveButton(R.string.comum_ok, null)
                .setNeutralButton(R.string.pin_esqueci) { _, _ -> esqueci(liberado) }
                .show()
            return
        }

        val vista = activity.layoutInflater.inflate(R.layout.dialogo_pin, null)
        vista.findViewById<TextView>(R.id.txtPinExplicacao).text = activity.getString(R.string.pin_digite, acao)
        val layoutPin = vista.findViewById<TextInputLayout>(R.id.layoutPin)
        val edtPin = vista.findViewById<TextInputEditText>(R.id.edtPin)

        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.pin_titulo)
            .setView(vista)
            .setPositiveButton(R.string.pin_confirmar, null)
            .setNegativeButton(R.string.comum_cancelar, null)
            .setNeutralButton(R.string.pin_esqueci, null)
            .create()

        // Botões tratados no onShow: com o PIN errado o diálogo continua aberto, mostrando o erro
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val (hash, sal) = AppConfig.getPin(activity) ?: return@setOnClickListener run { dialogo.dismiss(); liberado() }

                if (PinColetor.confere(edtPin.text.toString(), sal, hash)) {
                    AppConfig.limparTentativasPin(activity)
                    dialogo.dismiss()
                    liberado()
                    return@setOnClickListener
                }

                // O contador não zera no bloqueio: cada novo bloqueio dura mais (5 min, 15 min, 1 h...)
                val antes = AppConfig.getTentativasPin(activity)
                val depois = PinColetor.aposErro(antes, System.currentTimeMillis(), SystemClock.elapsedRealtime())
                AppConfig.saveTentativasPin(activity, depois)
                val erros = depois.erros
                edtPin.text = null
                if (erros % PinColetor.MAX_ERROS == 0) {
                    val minutos = depois.duracao / 60_000
                    Log.w(TAG, "PIN das Configurações errado $erros vezes desde o último acerto: bloqueado por $minutos min")
                    dialogo.dismiss()
                    Toast.makeText(activity, activity.getString(R.string.pin_errado_bloqueado, erros, minutos), Toast.LENGTH_LONG).show()
                } else {
                    val restam = PinColetor.restantesAteBloqueio(erros)
                    Log.w(TAG, "PIN das Configurações errado ($erros erro(s) desde o último acerto; $restam até o próximo bloqueio)")
                    layoutPin.error = activity.getString(R.string.pin_incorreto, restam)
                }
            }
            dialogo.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialogo.dismiss()
                esqueci(liberado)
            }
        }
        dialogo.show()
    }

    private fun criar(criado: () -> Unit) {
        val vista = activity.layoutInflater.inflate(R.layout.dialogo_pin, null)
        vista.findViewById<TextView>(R.id.txtPinExplicacao).text =
            activity.getString(R.string.pin_criar_explicacao, PinColetor.TAMANHO_MINIMO, PinColetor.TAMANHO_MAXIMO)
        val layoutPin = vista.findViewById<TextInputLayout>(R.id.layoutPin)
        val edtPin = vista.findViewById<TextInputEditText>(R.id.edtPin)
        val edtConfirmar = vista.findViewById<TextInputEditText>(R.id.edtPinConfirmar)
        vista.findViewById<View>(R.id.layoutPinConfirmar).visibility = View.VISIBLE
        layoutPin.hint = activity.getString(R.string.pin_novo)

        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.pin_criar_titulo)
            .setView(vista)
            .setPositiveButton(R.string.pin_criar, null)
            .setNegativeButton(R.string.comum_cancelar, null)
            .create()

        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pin = edtPin.text.toString()
                val problema = PinColetor.problemaDoNovo(pin, edtConfirmar.text.toString())
                if (problema != null) {
                    layoutPin.error = problema
                    return@setOnClickListener
                }

                val sal = PinColetor.novoSal()
                AppConfig.savePin(activity, PinColetor.hash(pin, sal), sal)
                Log.i(TAG, "PIN das Configurações criado neste coletor")
                Toast.makeText(activity, R.string.pin_criado, Toast.LENGTH_SHORT).show()
                dialogo.dismiss()
                criado()
            }
        }
        dialogo.show()
    }

    /** Um ADMIN confirma pelo servidor (email e senha); o PIN é apagado e um novo é criado em seguida. */
    private fun esqueci(liberado: () -> Unit) {
        val vista = activity.layoutInflater.inflate(R.layout.dialogo_liberar_pin, null)
        val edtEmail = vista.findViewById<TextInputEditText>(R.id.edtEmailAdmin)
        val edtSenha = vista.findViewById<TextInputEditText>(R.id.edtSenhaAdmin)

        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.pin_liberar_titulo)
            .setView(vista)
            .setPositiveButton(R.string.pin_liberar, null)
            .setNegativeButton(R.string.comum_cancelar, null)
            .create()

        dialogo.setOnShowListener {
            val botao = dialogo.getButton(AlertDialog.BUTTON_POSITIVE)
            botao.setOnClickListener {
                val email = edtEmail.text.toString().trim()
                val senha = edtSenha.text.toString()
                if (email.isEmpty() || senha.isEmpty()) {
                    Toast.makeText(activity, R.string.pin_informe_admin, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                botao.isEnabled = false
                tagService.liberarPin(email, senha) { resposta ->
                    activity.runOnUiThread {
                        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                        botao.isEnabled = true
                        when (resposta) {
                            is RespostaServidor.Sucesso -> {
                                AppConfig.apagarPin(activity)
                                Log.w(TAG, "PIN das Configurações apagado: liberado pelo administrador ${MascaraSensivel.email(email)}")
                                dialogo.dismiss()
                                criar(liberado)
                            }

                            is RespostaServidor.Falha -> {
                                edtSenha.text = null
                                Toast.makeText(activity, resposta.mensagem, Toast.LENGTH_LONG).show()
                            }

                            RespostaServidor.NaoEncontrado -> Unit
                        }
                    }
                }
            }
        }
        dialogo.show()
    }

    companion object {
        private const val TAG = "PIN"
    }
}
