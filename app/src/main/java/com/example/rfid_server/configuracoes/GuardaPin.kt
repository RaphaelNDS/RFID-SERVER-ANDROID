package com.example.rfid_server.configuracoes

import com.example.rfid_server.telas.CampoDialogo
import com.example.rfid_server.erros.MascaraSensivel
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.rfid_server.configuracoes.AppConfig
import com.example.rfid_server.configuracoes.PinColetor
import com.example.rfid_server.R
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Diálogos do PIN das Configurações (PinColetor): pedir o PIN antes de trocar o servidor, a chave ou o
 * certificado; criar o PIN na primeira vez; e "Esqueci o PIN", que um ADMIN libera pelo servidor ou o responsável
 * libera com a chave do coletor (troca de servidor: o servidor novo ainda não conecta).
 */
class GuardaPin(private val activity: AppCompatActivity, private val tagService: TagService) {

    // Quando o PIN foi aceito (ou criado/liberado) nesta tela; só em memória: sair da tela zera
    private var liberadoEm: Long? = null

    /** Pede o PIN e chama [liberado] se estiver certo. Coletor ainda sem PIN: cria um antes (é obrigatório). */
    fun exigir(acao: String, liberado: () -> Unit) {
        if (carenciaValida(acao, liberado)) return
        if (AppConfig.temPin(activity)) pedir(acao, marcando(liberado)) else criar(marcando(liberado))
    }

    /** Pede o PIN só se já existir um (ex.: confirmar o certificado na primeira configuração, antes de salvar). */
    fun exigirSeTiver(acao: String, liberado: () -> Unit) {
        if (carenciaValida(acao, liberado)) return
        if (AppConfig.temPin(activity)) pedir(acao, marcando(liberado)) else liberado()
    }

    // PIN acabou de ser digitado nesta tela (ex.: salvou o servidor novo e o certificado dele aparece logo depois)
    private fun carenciaValida(acao: String, liberado: () -> Unit): Boolean {
        if (!PinColetor.dentroDaCarencia(liberadoEm, SystemClock.elapsedRealtime())) return false
        Log.i(TAG, "PIN não pedido de novo para $acao: confirmado nesta tela há menos de ${PinColetor.CARENCIA_MS / 60_000} min")
        liberado()
        return true
    }

    private fun marcando(liberado: () -> Unit): () -> Unit = {
        liberadoEm = SystemClock.elapsedRealtime()
        liberado()
    }

    private fun pedir(acao: String, liberado: () -> Unit) {
        // Relógio de parede e tempo desde a ligação: adiantar o relógio do Android não acaba com o bloqueio
        AppConfig.limparBloqueioPinVencido(activity)
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
            .create()

        // Esqueci o PIN: no corpo do diálogo (ver dialogo_pin.xml), para Cancelar e Confirmar caberem lado a lado
        vista.findViewById<View>(R.id.btnEsqueciPin).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                dialogo.dismiss()
                esqueci(liberado)
            }
        }

        // Abre com o teclado numérico na tela; o Concluir do teclado confere o PIN como o botão
        CampoDialogo.confirmarPeloTeclado(dialogo, edtPin)

        // Botões tratados no onShow: com o PIN errado o diálogo continua aberto, mostrando o erro
        dialogo.setOnShowListener {
            CampoDialogo.focarComTeclado(dialogo, edtPin)
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

        // PIN novo → Próximo vai para o "Repita o PIN"; lá o Concluir cria
        CampoDialogo.confirmarPeloTeclado(dialogo, edtConfirmar)
        edtPin.imeOptions = EditorInfo.IME_ACTION_NEXT

        dialogo.setOnShowListener {
            CampoDialogo.focarComTeclado(dialogo, edtPin)
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

        // Na troca de servidor o servidor novo não conecta: a chave do coletor libera sem o servidor
        val temChave = AppConfig.chaveConfigurada(activity)
        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.pin_liberar_titulo)
            .setView(vista)
            .setPositiveButton(R.string.pin_liberar, null)
            .setNegativeButton(R.string.comum_cancelar, null)
            .create()

        if (temChave) {
            vista.findViewById<View>(R.id.btnLiberarComChave).apply {
                visibility = View.VISIBLE
                setOnClickListener {
                    dialogo.dismiss()
                    liberarComChave(liberado)
                }
            }
        }

        // Email → Próximo → senha; na senha o Concluir libera
        CampoDialogo.confirmarPeloTeclado(dialogo, edtSenha)

        dialogo.setOnShowListener {
            CampoDialogo.focarComTeclado(dialogo, edtEmail)
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
                        // O operador cancelou enquanto o servidor respondia: não apaga o PIN nem abre a criação de outro
                        // (antes apagava, e cancelando a criação o coletor ficava sem PIN)
                        if (!dialogo.isShowing) {
                            Log.i(TAG, "Liberação do PIN respondida depois de cancelada: ignorada, o PIN continua")
                            return@runOnUiThread
                        }
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

    /**
     * Libera o PIN com a chave do coletor (a mesma cadastrada no servidor), sem precisar do servidor. Os erros
     * contam junto com os do PIN (PinColetor.liberarComChave): bloqueado o PIN, a chave também não é conferida.
     */
    private fun liberarComChave(liberado: () -> Unit) {
        val vista = activity.layoutInflater.inflate(R.layout.dialogo_pin_chave, null)
        val layoutChave = vista.findViewById<TextInputLayout>(R.id.layoutChavePin)
        val edtChave = vista.findViewById<TextInputEditText>(R.id.edtChavePin)

        val dialogo = AlertDialog.Builder(activity)
            .setTitle(R.string.pin_liberar_titulo)
            .setView(vista)
            .setPositiveButton(R.string.pin_liberar, null)
            .setNegativeButton(R.string.comum_cancelar, null)
            .create()

        CampoDialogo.confirmarPeloTeclado(dialogo, edtChave)

        dialogo.setOnShowListener {
            CampoDialogo.focarComTeclado(dialogo, edtChave)
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val digitada = edtChave.text.toString()
                if (digitada.isBlank()) {
                    layoutChave.error = activity.getString(R.string.pin_chave_informe)
                    return@setOnClickListener
                }

                AppConfig.limparBloqueioPinVencido(activity)
                val resultado = PinColetor.liberarComChave(
                    digitada, AppConfig.getChaveDispositivo(activity), AppConfig.getTentativasPin(activity),
                    System.currentTimeMillis(), SystemClock.elapsedRealtime()
                )
                edtChave.text = null
                when (resultado) {
                    PinColetor.LiberacaoPorChave.Liberado -> {
                        AppConfig.apagarPin(activity)
                        Log.w(TAG, "PIN das Configurações apagado: liberado com a chave do coletor")
                        dialogo.dismiss()
                        criar(liberado)
                    }

                    is PinColetor.LiberacaoPorChave.Bloqueado -> {
                        val minutos = (resultado.restanteMs - 1) / 60_000 + 1
                        Log.w(TAG, "Liberação do PIN pela chave recusada: PIN bloqueado por mais $minutos minuto(s)")
                        dialogo.dismiss()
                        Toast.makeText(activity, activity.getString(R.string.pin_chave_bloqueado, minutos), Toast.LENGTH_LONG).show()
                    }

                    is PinColetor.LiberacaoPorChave.Errada -> {
                        val depois = resultado.estado
                        AppConfig.saveTentativasPin(activity, depois)
                        val erros = depois.erros
                        if (erros % PinColetor.MAX_ERROS == 0) {
                            val minutos = depois.duracao / 60_000
                            Log.w(TAG, "Chave do coletor errada ao liberar o PIN ($erros erro(s) desde o último acerto): bloqueado por $minutos min")
                            dialogo.dismiss()
                            Toast.makeText(activity, activity.getString(R.string.pin_chave_errada_bloqueado, minutos), Toast.LENGTH_LONG).show()
                        } else {
                            val restam = PinColetor.restantesAteBloqueio(erros)
                            Log.w(TAG, "Chave do coletor errada ao liberar o PIN ($erros erro(s) desde o último acerto; $restam até o próximo bloqueio)")
                            layoutChave.error = activity.getString(R.string.pin_chave_errada, restam)
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
