package com.example.rfid_server.activities

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
        val bloqueadoAte = AppConfig.getPinBloqueadoAte(activity)
        val agora = System.currentTimeMillis()
        if (bloqueadoAte > agora) {
            val minutos = (bloqueadoAte - agora) / 60_000 + 1
            AlertDialog.Builder(activity)
                .setTitle("PIN bloqueado")
                .setMessage("Muitas tentativas erradas. Aguarde $minutos minuto(s) ou peça a um administrador para liberar o PIN.")
                .setPositiveButton("OK", null)
                .setNeutralButton("Esqueci o PIN") { _, _ -> esqueci(liberado) }
                .show()
            return
        }

        val vista = activity.layoutInflater.inflate(R.layout.dialogo_pin, null)
        vista.findViewById<TextView>(R.id.txtPinExplicacao).text = "Digite o PIN das Configurações para $acao."
        val layoutPin = vista.findViewById<TextInputLayout>(R.id.layoutPin)
        val edtPin = vista.findViewById<TextInputEditText>(R.id.edtPin)

        val dialogo = AlertDialog.Builder(activity)
            .setTitle("PIN do coletor")
            .setView(vista)
            .setPositiveButton("Confirmar", null)
            .setNegativeButton("Cancelar", null)
            .setNeutralButton("Esqueci o PIN", null)
            .create()

        // Botões tratados no onShow: com o PIN errado o diálogo continua aberto, mostrando o erro
        dialogo.setOnShowListener {
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val (hash, sal) = AppConfig.getPin(activity) ?: return@setOnClickListener run { dialogo.dismiss(); liberado() }

                if (PinColetor.confere(edtPin.text.toString(), sal, hash)) {
                    AppConfig.saveTentativasPin(activity, 0, 0)
                    dialogo.dismiss()
                    liberado()
                    return@setOnClickListener
                }

                val erros = AppConfig.getErrosPin(activity) + 1
                val bloqueio = PinColetor.bloqueioAposErro(erros, System.currentTimeMillis())
                edtPin.text = null
                if (bloqueio != null) {
                    AppConfig.saveTentativasPin(activity, 0, bloqueio)
                    Log.w(TAG, "PIN das Configurações errado $erros vezes seguidas: bloqueado por ${PinColetor.BLOQUEIO_MS / 60_000} min")
                    dialogo.dismiss()
                    Toast.makeText(activity, "PIN errado $erros vezes: bloqueado por ${PinColetor.BLOQUEIO_MS / 60_000} minutos", Toast.LENGTH_LONG).show()
                } else {
                    AppConfig.saveTentativasPin(activity, erros, 0)
                    Log.w(TAG, "PIN das Configurações errado (tentativa $erros de ${PinColetor.MAX_ERROS})")
                    layoutPin.error = "PIN incorreto. Restam ${PinColetor.MAX_ERROS - erros} tentativa(s)"
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
            "Crie um PIN de ${PinColetor.TAMANHO_MINIMO} a ${PinColetor.TAMANHO_MAXIMO} números. Ele protege o servidor, " +
                "a chave e o certificado deste coletor. Guarde-o com o responsável: sem ele, só um administrador libera."
        val layoutPin = vista.findViewById<TextInputLayout>(R.id.layoutPin)
        val edtPin = vista.findViewById<TextInputEditText>(R.id.edtPin)
        val edtConfirmar = vista.findViewById<TextInputEditText>(R.id.edtPinConfirmar)
        vista.findViewById<View>(R.id.layoutPinConfirmar).visibility = View.VISIBLE
        layoutPin.hint = "PIN novo"

        val dialogo = AlertDialog.Builder(activity)
            .setTitle("Criar o PIN do coletor")
            .setView(vista)
            .setPositiveButton("Criar", null)
            .setNegativeButton("Cancelar", null)
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
                Toast.makeText(activity, "PIN criado", Toast.LENGTH_SHORT).show()
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
            .setTitle("Liberar o PIN")
            .setView(vista)
            .setPositiveButton("Liberar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        dialogo.setOnShowListener {
            val botao = dialogo.getButton(AlertDialog.BUTTON_POSITIVE)
            botao.setOnClickListener {
                val email = edtEmail.text.toString().trim()
                val senha = edtSenha.text.toString()
                if (email.isEmpty() || senha.isEmpty()) {
                    Toast.makeText(activity, "Informe o email e a senha do administrador", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                botao.isEnabled = false
                tagService.liberarPin(email, senha) { resposta ->
                    activity.runOnUiThread {
                        if (activity.isFinishing) return@runOnUiThread
                        botao.isEnabled = true
                        when (resposta) {
                            is RespostaServidor.Sucesso -> {
                                AppConfig.apagarPin(activity)
                                Log.w(TAG, "PIN das Configurações apagado: liberado pelo administrador $email")
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
