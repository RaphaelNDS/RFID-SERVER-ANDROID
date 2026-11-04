package com.example.rfid_server.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import com.example.rfid_server.AppConfig
import com.example.rfid_server.BuildConfig
import com.example.rfid_server.R
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
            if (manual) toast(activity.getString(R.string.atualizacao_configure_servidor))
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
                            if (manual) toast(activity.getString(R.string.atualizacao_ja_mais_nova, BuildConfig.VERSION_NAME))
                        }
                    }

                    RespostaServidor.NaoEncontrado -> {
                        Log.d(TAG, "Nenhuma versão do app publicada no servidor")
                        if (manual) toast(activity.getString(R.string.atualizacao_nenhuma_publicada))
                    }

                    is RespostaServidor.Falha -> {
                        Log.w(TAG, "Não foi possível consultar a atualização do app: ${resposta.mensagem}")
                        if (manual) toast(activity.getString(R.string.atualizacao_falha_consulta, resposta.mensagem))
                    }
                }
            }
        }
    }

    private fun oferecer(versao: VersaoPublicada) {
        Log.i(TAG, "Atualização disponível: instalada ${BuildConfig.VERSION_NAME} (código ${BuildConfig.VERSION_CODE}), " +
            "publicada ${versao.versaoNome} (código ${versao.codigo})")

        val mensagem = buildString {
            // Mesmo nome de versão (ex.: correção publicada sem mudar o nome): o código mostra qual é a mais nova
            if (versao.versaoNome == BuildConfig.VERSION_NAME) {
                append(activity.getString(R.string.atualizacao_versao_mesmo_nome, versao.versaoNome, versao.codigo, BuildConfig.VERSION_CODE))
            } else {
                append(activity.getString(R.string.atualizacao_versao, versao.versaoNome, BuildConfig.VERSION_NAME))
            }
            append(" ").append(activity.getString(R.string.atualizacao_download, versao.tamanhoLegivel()))
            versao.notas?.let { append("\n\n").append(activity.getString(R.string.atualizacao_o_que_mudou, it)) }
            append("\n\n").append(activity.getString(R.string.atualizacao_dados_continuam))
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.atualizacao_titulo)
            .setMessage(mensagem)
            .setPositiveButton(R.string.atualizacao_atualizar_agora) { _, _ -> iniciar(versao) }
            .setNegativeButton(R.string.comum_depois) { _, _ -> Log.i(TAG, "Operador adiou a atualização para a versão ${versao.versaoNome}") }
            .setOnCancelListener { Log.i(TAG, "Operador fechou o aviso da atualização para a versão ${versao.versaoNome} sem escolher (oferecida de novo mais tarde)") }
            .show()
    }

    private fun iniciar(versao: VersaoPublicada) {
        // O Android exige que o app seja autorizado (uma vez) a instalar atualizações
        if (!activity.packageManager.canRequestPackageInstalls()) {
            Log.i(TAG, "App sem autorização para instalar atualizações: pedindo ao operador")
            AlertDialog.Builder(activity)
                .setTitle(R.string.atualizacao_autorizar_titulo)
                .setMessage(R.string.atualizacao_autorizar_mensagem)
                .setPositiveButton(R.string.atualizacao_abrir_autorizacao) { _, _ ->
                    aguardandoPermissao = true
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri()))
                }
                .setNegativeButton(R.string.comum_cancelar, null)
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
            .setTitle(activity.getString(R.string.atualizacao_baixando, versao.versaoNome))
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
            // Ler o APK leva um tempo: confere aqui, fora da thread da tela
            val recusa = (resposta as? RespostaServidor.Sucesso)?.let { conferirApk(versao, it.valor) }

            activity.runOnUiThread {
                if (dialogo.isShowing) dialogo.dismiss()
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread

                when (resposta) {
                    is RespostaServidor.Sucesso ->
                        if (recusa == null) instalar(versao, resposta.valor) else recusar(versao, resposta.valor, recusa)
                    is RespostaServidor.Falha -> {
                        Log.e(TAG, "Atualização para a versão ${versao.versaoNome} não baixada: ${resposta.mensagem}")
                        RegistroErros.registrar(activity, TipoErro.ATUALIZACAO,
                            "Atualização para a versão ${versao.versaoNome} (código ${versao.codigo}) não baixada: ${resposta.mensagem}")
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.atualizacao_nao_baixada)
                            .setMessage(resposta.mensagem)
                            .setPositiveButton(R.string.comum_ok, null)
                            .show()
                    }
                    RespostaServidor.NaoEncontrado -> Unit
                }
            }
        }.start()
    }

    /**
     * Confere o APK baixado contra o app instalado antes de abrir o instalador: o SHA-256 vem do mesmo servidor
     * que o APK, então só ele não protege contra um servidor trocado ou invadido. Retorna o motivo da recusa,
     * ou null se o APK é deste app, assinado com a mesma chave e com o código de versão anunciado.
     */
    private fun conferirApk(versao: VersaoPublicada, apk: File): String? = try {
        val pm = activity.packageManager
        val doApk = lerPacote { flags -> pm.getPackageArchiveInfo(apk.absolutePath, flags) }
        val instalado = lerPacote { flags -> pm.getPackageInfo(activity.packageName, flags) }
        ConferenciaApk.problema(
            pacoteInstalado = activity.packageName,
            certificadosInstalado = instalado?.let(::certificados).orEmpty(),
            codigoAnunciado = versao.codigo.toLong(),
            pacoteApk = doApk?.packageName,
            certificadosApk = doApk?.let(::certificados).orEmpty(),
            codigoApk = doApk?.let { PackageInfoCompat.getLongVersionCode(it) }
        )
    } catch (e: Exception) {
        Log.e(TAG, "Erro ao conferir o APK baixado da versão ${versao.versaoNome}", e)
        "não foi possível ler o arquivo baixado (${e.javaClass.simpleName})"
    }

    @Suppress("DEPRECATION")
    private fun lerPacote(ler: (Int) -> PackageInfo?): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) ler(PackageManager.GET_SIGNING_CERTIFICATES)
        else ler(PackageManager.GET_SIGNATURES)

    /** SHA-256 (hex) dos certificados que assinam o pacote agora (com rotação de chave, o atual). */
    @Suppress("DEPRECATION")
    private fun certificados(info: PackageInfo): Set<String> {
        val assinaturas: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return assinaturas.orEmpty().map { ConferenciaApk.sha256Hex(it.toByteArray()) }.toSet()
    }

    private fun recusar(versao: VersaoPublicada, apk: File, motivo: String) {
        apk.delete()
        Log.e(TAG, "Atualização para a versão ${versao.versaoNome} (código ${versao.codigo}) RECUSADA: $motivo. APK apagado")
        RegistroErros.registrar(activity, TipoErro.ATUALIZACAO,
            "Atualização para a versão ${versao.versaoNome} (código ${versao.codigo}) recusada: $motivo")
        AlertDialog.Builder(activity)
            .setTitle(R.string.atualizacao_recusada_titulo)
            .setMessage(activity.getString(R.string.atualizacao_recusada_mensagem, motivo))
            .setPositiveButton(R.string.comum_ok, null)
            .show()
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
            toast(activity.getString(R.string.atualizacao_sem_instalador))
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

/** Decisão (sem Android) de aceitar ou não o APK baixado, comparando com o app instalado. */
object ConferenciaApk {

    /** Motivo da recusa em português, ou null se o APK pode ir para o instalador. */
    fun problema(
        pacoteInstalado: String,
        certificadosInstalado: Set<String>,
        codigoAnunciado: Long,
        pacoteApk: String?,
        certificadosApk: Set<String>,
        codigoApk: Long?
    ): String? = when {
        pacoteApk == null || codigoApk == null -> "o arquivo baixado não é um APK válido"
        pacoteApk != pacoteInstalado -> "o APK é de outro app ($pacoteApk, esperado $pacoteInstalado)"
        certificadosInstalado.isEmpty() -> "não foi possível ler a assinatura do app instalado"
        certificadosApk.isEmpty() -> "o APK não tem assinatura legível"
        certificadosApk != certificadosInstalado -> "o APK foi assinado com outra chave, diferente da do app instalado"
        codigoApk != codigoAnunciado -> "o código de versão do APK ($codigoApk) é diferente do anunciado pelo servidor ($codigoAnunciado)"
        else -> null
    }

    fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
