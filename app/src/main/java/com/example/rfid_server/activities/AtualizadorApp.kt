package com.example.rfid_server.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.example.rfid_server.AppConfig
import com.example.rfid_server.BuildConfig
import com.example.rfid_server.dados.VersaoPublicada
import com.example.rfid_server.erros.RegistroErros
import com.example.rfid_server.erros.TipoErro
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService
import java.io.File

/**
 * Atualização do app pelo servidor: consulta a versão publicada, oferece ao operador, baixa o APK (conferindo
 * o SHA-256) e abre o instalador do Android. O operador sempre confirma a instalação (exigência do Android),
 * e o Android só aceita o APK assinado com a mesma chave do app instalado. Os dados do coletor (fila de
 * leituras, configurações) continuam depois da atualização.
 *
 * Automática (tela de login): no máximo uma consulta a cada [INTERVALO_AUTOMATICO_MS]. Manual (Configurações):
 * sempre, e avisa também quando já está na versão mais nova.
 */
class AtualizadorApp(private val activity: AppCompatActivity) {

    fun verificar(manual: Boolean) {
        if (!AppConfig.servidorConfigurado(activity)) {
            if (manual) toast("Configure o servidor antes de procurar atualização")
            return
        }

        val agora = System.currentTimeMillis()
        if (!manual && !aguardandoPermissao && agora - ultimaConsultaAutomatica < INTERVALO_AUTOMATICO_MS) return
        ultimaConsultaAutomatica = agora
        aguardandoPermissao = false

        TagService(activity).consultarAtualizacao { resposta ->
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

                when (resposta) {
                    is RespostaServidor.Sucesso -> {
                        val publicada = resposta.valor
                        if (publicada.maisNovaQue(BuildConfig.VERSION_CODE)) {
                            oferecer(publicada)
                        } else {
                            Log.d(TAG, "App já está atualizado: instalado código ${BuildConfig.VERSION_CODE}, publicado ${publicada.codigo}")
                            if (manual) toast("O app já está na versão mais nova (${BuildConfig.VERSION_NAME})")
                        }
                    }

                    RespostaServidor.NaoEncontrado -> {
                        Log.d(TAG, "Nenhuma versão do app publicada no servidor")
                        if (manual) toast("Nenhuma versão do app publicada no servidor")
                    }

                    is RespostaServidor.Falha -> {
                        Log.w(TAG, "Não foi possível consultar a atualização do app: ${resposta.mensagem}")
                        if (manual) toast("Não foi possível procurar atualização: ${resposta.mensagem}")
                    }
                }
            }
        }
    }

    private fun oferecer(versao: VersaoPublicada) {
        Log.i(TAG, "Atualização disponível: instalada ${BuildConfig.VERSION_NAME} (código ${BuildConfig.VERSION_CODE}), " +
            "publicada ${versao.versaoNome} (código ${versao.codigo})")

        val mensagem = buildString {
            append("Versão ${versao.versaoNome} disponível (agora: ${BuildConfig.VERSION_NAME}). Download de ${versao.tamanhoLegivel()}.")
            versao.notas?.let { append("\n\nO que mudou: $it") }
            append("\n\nO que está guardado no coletor (leituras aguardando envio, configurações) continua depois da atualização.")
        }

        AlertDialog.Builder(activity)
            .setTitle("Atualização do app")
            .setMessage(mensagem)
            .setPositiveButton("Atualizar agora") { _, _ -> iniciar(versao) }
            .setNegativeButton("Depois") { _, _ -> Log.i(TAG, "Operador adiou a atualização para a versão ${versao.versaoNome}") }
            .show()
    }

    private fun iniciar(versao: VersaoPublicada) {
        // O Android exige que o app seja autorizado (uma vez) a instalar atualizações
        if (!activity.packageManager.canRequestPackageInstalls()) {
            Log.i(TAG, "App sem autorização para instalar atualizações: pedindo ao operador")
            AlertDialog.Builder(activity)
                .setTitle("Autorizar atualizações")
                .setMessage("O Android pede que você autorize este app a instalar atualizações (uma vez só).\n\n" +
                    "Na próxima tela, ative \"Permitir desta fonte\" e volte para o app: a atualização é oferecida de novo.")
                .setPositiveButton("Abrir autorização") { _, _ ->
                    aguardandoPermissao = true
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                }
                .setNegativeButton("Cancelar", null)
                .show()
            return
        }
        baixar(versao)
    }

    private fun baixar(versao: VersaoPublicada) {
        val barra = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = (versao.tamanho / 1024).toInt().coerceAtLeast(1)
            setPadding(48, 24, 48, 0)
        }
        val dialogo = AlertDialog.Builder(activity)
            .setTitle("Baixando a versão ${versao.versaoNome}")
            .setView(barra)
            .setCancelable(false)
            .show()

        Log.i(TAG, "Baixando a versão ${versao.versaoNome} (código ${versao.codigo}, ${versao.tamanhoLegivel()})")

        Thread {
            // Só um APK por vez na pasta: apaga downloads antigos ou interrompidos
            val pasta = File(activity.cacheDir, PASTA)
            pasta.listFiles()?.forEach { it.delete() }
            val destino = File(pasta, "rfid-android-${versao.codigo}.apk")

            val resposta = TagService(activity).baixarAtualizacaoAgora(versao, destino) { bytes ->
                activity.runOnUiThread { barra.progress = (bytes / 1024).toInt() }
            }

            activity.runOnUiThread {
                if (dialogo.isShowing) dialogo.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

                when (resposta) {
                    is RespostaServidor.Sucesso -> instalar(versao, resposta.valor)
                    is RespostaServidor.Falha -> {
                        Log.e(TAG, "Atualização para a versão ${versao.versaoNome} não baixada: ${resposta.mensagem}")
                        RegistroErros.registrar(activity, TipoErro.ATUALIZACAO,
                            "Atualização para a versão ${versao.versaoNome} (código ${versao.codigo}) não baixada: ${resposta.mensagem}")
                        AlertDialog.Builder(activity)
                            .setTitle("Atualização não baixada")
                            .setMessage(resposta.mensagem)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                    RespostaServidor.NaoEncontrado -> Unit
                }
            }
        }.start()
    }

    private fun instalar(versao: VersaoPublicada, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.atualizacao", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, TIPO_APK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            activity.startActivity(intent)
            Log.i(TAG, "Instalador do Android aberto para a versão ${versao.versaoNome} (código ${versao.codigo})")
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Este aparelho não tem instalador de pacotes disponível", e)
            toast("Não foi possível abrir o instalador do Android")
        }
    }

    private fun toast(texto: String) = Toast.makeText(activity, texto, Toast.LENGTH_LONG).show()

    companion object {
        private const val TAG = "ATUALIZACAO"
        private const val PASTA = "atualizacao"
        private const val TIPO_APK = "application/vnd.android.package-archive"

        /** Consulta automática (tela de login) no máximo a cada 30 min. */
        private const val INTERVALO_AUTOMATICO_MS = 30 * 60 * 1000L

        @Volatile private var ultimaConsultaAutomatica = 0L

        // Operador foi à tela de autorização: ao voltar, oferece de novo sem esperar o intervalo
        @Volatile private var aguardandoPermissao = false
    }
}
