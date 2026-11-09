package com.example.rfid_server.activities

import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.StatFs
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.rfid_server.AppConfig
import com.example.rfid_server.RfidManager
import com.example.rfid_server.SessaoAtual
import com.example.rfid_server.VersaoApp
import com.example.rfid_server.api.RelogioServidor
import com.example.rfid_server.api.UltimaSincronizacaoOk
import com.example.rfid_server.dados.BancoLocal
import com.example.rfid_server.erros.RegistroErros
import com.example.rfid_server.sincronizacao.SincronizadorJustificativas
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/** O que o diagnóstico mostra (montado em [Diagnostico.coletar], formatado em [Diagnostico.texto]). */
data class DadosDiagnostico(
    val versaoApp: String,
    val android: String,
    val modelo: String,
    val servidor: String?,
    val certificado: String?,
    val operador: String?,
    val sessaoAte: Long?,
    val leitor: String,
    val bateria: Int?,
    val leiturasPendentes: Int,
    val itensInventarioPendentes: Int,
    val cadastrosPendentes: Int,
    val justificativasPendentes: Int,
    val errosGuardados: Int,
    val leituraMaisAntiga: Long?,
    val ultimaSincronizacaoOk: Long?,
    val desvioRelogioMs: Long?,
    val espacoLivreMb: Long?,
    val agora: Long
)

/**
 * Diagnóstico do coletor (Configurações): tudo o que o responsável precisa para entender um problema por telefone, sem
 * adb: versão, servidor, leitor, fila pendente, último envio, relógio e espaço livre. Pode ser compartilhado (texto).
 */
object Diagnostico {

    private const val TAG = "DIAGNOSTICO"
    private val FORMATO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    fun mostrar(activity: AppCompatActivity) {
        if (!CliqueUnico.permitir()) return
        val executor = Executors.newSingleThreadExecutor()
        executor.execute {
            val dados = try {
                coletar(activity)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao montar o diagnóstico do coletor", e)
                null
            } finally {
                executor.shutdown()
            }
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed || dados == null) return@runOnUiThread
                val texto = texto(dados)
                Log.i(TAG, "Diagnóstico do coletor aberto:\n$texto")
                val dialogo = AlertDialog.Builder(activity)
                    .setTitle(com.example.rfid_server.R.string.diagnostico_titulo)
                    .setMessage(texto)
                    .setPositiveButton(com.example.rfid_server.R.string.diagnostico_compartilhar) { _, _ -> compartilhar(activity, texto) }
                    .setNegativeButton(com.example.rfid_server.R.string.comum_ok, null)
                    .show()
                dialogo.findViewById<TextView>(android.R.id.message)?.apply {
                    typeface = Typeface.MONOSPACE
                    textSize = 13f
                }
            }
        }
    }

    // Fora da thread principal (consulta o banco local)
    private fun coletar(activity: AppCompatActivity): DadosDiagnostico {
        val app = activity.applicationContext
        val banco = BancoLocal.get(app)
        val rfid = RfidManager.get(app)
        val sessao = SessaoAtual.get(app)
        return DadosDiagnostico(
            versaoApp = VersaoApp.descricao(),
            android = Build.VERSION.RELEASE.orEmpty(),
            modelo = Build.MODEL.orEmpty(),
            servidor = AppConfig.getBaseUrl(app).ifEmpty { null },
            certificado = AppConfig.getImpressaoDigital(app),
            operador = sessao?.nome,
            sessaoAte = sessao?.expiraEm,
            leitor = rfid.estado.value?.descricao ?: "—",
            bateria = rfid.detalhe.value?.bateria,
            leiturasPendentes = banco.leituraDao().contarPendentesAgora(),
            itensInventarioPendentes = banco.inventarioDao().contarItensNaoEnviadosAgora(),
            cadastrosPendentes = banco.cadastroDao().contarPendentesAgora(),
            justificativasPendentes = SincronizadorJustificativas.fila(app).pendentes().size,
            errosGuardados = RegistroErros.quantidadeGuardada(app),
            leituraMaisAntiga = banco.leituraDao().lidoEmPendenteMaisAntiga(),
            ultimaSincronizacaoOk = UltimaSincronizacaoOk.momento,
            desvioRelogioMs = RelogioServidor.desvio.value,
            espacoLivreMb = try { StatFs(app.filesDir.path).availableBytes / (1024 * 1024) } catch (e: Exception) { null },
            agora = System.currentTimeMillis()
        )
    }

    /** Texto do diagnóstico (testado no computador). */
    fun texto(d: DadosDiagnostico, zona: ZoneId = ZoneId.systemDefault()): String = buildString {
        fun data(ms: Long?) = ms?.let { FORMATO.format(Instant.ofEpochMilli(it).atZone(zona)) } ?: "—"
        appendLine("App: ${d.versaoApp}")
        appendLine("Aparelho: ${d.modelo} · Android ${d.android}")
        appendLine("Gerado em: ${data(d.agora)}")
        appendLine()
        appendLine("Servidor: ${d.servidor ?: "não configurado"}")
        appendLine("Certificado: ${d.certificado?.let { it.take(11) + "…" + it.takeLast(11) } ?: "não confirmado"}")
        appendLine("Operador: ${d.operador?.let { "$it (até ${data(d.sessaoAte)})" } ?: "ninguém logado"}")
        appendLine()
        appendLine("Leitor: ${d.leitor}" + (d.bateria?.let { " · bateria $it%" } ?: ""))
        appendLine()
        val total = d.leiturasPendentes + d.itensInventarioPendentes + d.cadastrosPendentes + d.justificativasPendentes
        appendLine("Aguardando envio: $total")
        appendLine("  leituras ${d.leiturasPendentes} · inventário ${d.itensInventarioPendentes}")
        appendLine("  cadastros ${d.cadastrosPendentes} · justificativas ${d.justificativasPendentes}")
        if (d.leituraMaisAntiga != null) appendLine("  leitura mais antiga: ${data(d.leituraMaisAntiga)}")
        appendLine("Erros guardados para o servidor: ${d.errosGuardados}")
        appendLine("Último envio completo: ${d.ultimaSincronizacaoOk?.let(::data) ?: "nenhum desde que o app abriu"}")
        appendLine()
        appendLine("Relógio: ${d.desvioRelogioMs?.let { if (kotlin.math.abs(it) < 60_000) "certo" else RelogioServidor.descrever(it) } ?: "ainda não comparado com o servidor"}")
        append("Espaço livre: ${d.espacoLivreMb?.let { "$it MB" } ?: "—"}")
    }

    private fun compartilhar(activity: AppCompatActivity, texto: String) {
        val envio = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, activity.getString(com.example.rfid_server.R.string.diagnostico_titulo))
            .putExtra(Intent.EXTRA_TEXT, texto)
        try {
            activity.startActivity(Intent.createChooser(envio, activity.getString(com.example.rfid_server.R.string.diagnostico_compartilhar)))
        } catch (e: Exception) {
            Log.w(TAG, "Nenhum app para compartilhar o diagnóstico", e)
        }
    }
}
