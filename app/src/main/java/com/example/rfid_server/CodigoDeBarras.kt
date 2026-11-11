package com.example.rfid_server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Leitor de código de barras do coletor (DataWedge da Zebra) entregando o código ao app, em vez de "digitar" no campo
 * com foco. Assim cada tela decide o que fazer com o que foi lido: o Localizar busca o patrimônio, o Inventário
 * preenche o local, as Configurações leem o QR de configuração...
 *
 * [configurarPerfil] cria (uma vez) e atualiza o perfil "RFID-Android" do DataWedge, ligado só a este app: leitor de
 * código de barras ligado, saída por broadcast ([acao]), saída de teclado desligada (senão o código chegaria duas vezes)
 * e entrada RFID do DataWedge desligada (o RFID é do próprio app, pelo SDK). Sem DataWedge (outro aparelho), nada acontece.
 */
object CodigoDeBarras {

    private const val TAG = "CODIGO_BARRAS"
    // Ação própria de cada app (produção e teste ficam lado a lado no coletor e não recebem os códigos um do outro)
    fun acao(context: Context): String = context.packageName + ".CODIGO_LIDO"
    private const val EXTRA_DADOS = "com.symbol.datawedge.data_string"
    // Um perfil por app (o DataWedge associa cada app a um perfil só)
    private fun perfil(context: Context) = if (context.packageName.endsWith(".teste")) "RFID-Android-teste" else "RFID-Android"

    fun configurarPerfil(context: Context) {
        try {
            val app = Bundle().apply {
                putString("PACKAGE_NAME", context.packageName)
                putStringArray("ACTIVITY_LIST", arrayOf("*"))
            }
            val plugins = arrayListOf(
                plugin("BARCODE", "scanner_input_enabled" to "true", "scanner_selection" to "auto"),
                plugin("INTENT", "intent_output_enabled" to "true", "intent_action" to acao(context), "intent_delivery" to "2"),
                plugin("KEYSTROKE", "keystroke_output_enabled" to "false"),
                plugin("RFID", "rfid_input_enabled" to "false")
            )
            // Duas vezes: CREATE_IF_NOT_EXIST cria o perfil na primeira vez, mas pode não mexer num perfil que já existe;
            // o UPDATE em seguida garante que uma versão nova do app com outra configuração chegue aos coletores
            for (modo in listOf("CREATE_IF_NOT_EXIST", "UPDATE")) {
                val config = Bundle().apply {
                    putString("PROFILE_NAME", perfil(context))
                    putString("PROFILE_ENABLED", "true")
                    putString("CONFIG_MODE", modo)
                    putParcelableArray("APP_LIST", arrayOf(app))
                    putParcelableArrayList("PLUGIN_CONFIG", plugins)
                }
                context.sendBroadcast(
                    Intent("com.symbol.datawedge.api.ACTION")
                        .setPackage("com.symbol.datawedge")
                        .putExtra("com.symbol.datawedge.api.SET_CONFIG", config)
                )
            }
            Log.i(TAG, "Perfil ${perfil(context)} do DataWedge configurado: código de barras entregue ao app")
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível configurar o DataWedge: o leitor de código de barras segue como teclado", e)
        }
    }

    /**
     * Liga o leitor de código de barras agora, como se o gatilho fosse apertado (SOFT_SCAN_TRIGGER do DataWedge): o
     * código lido chega pelo [ouvir], como os outros. Funciona mesmo com o gatilho físico preso ao RFID. false = sem
     * DataWedge neste aparelho (não é um coletor Zebra).
     */
    fun lerAgora(context: Context): Boolean {
        try {
            context.packageManager.getPackageInfo(PACOTE_DATAWEDGE, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Sem DataWedge neste aparelho: não dá para ligar o leitor de código de barras")
            return false
        }
        return try {
            context.sendBroadcast(
                Intent("com.symbol.datawedge.api.ACTION")
                    .setPackage(PACOTE_DATAWEDGE)
                    .putExtra("com.symbol.datawedge.api.SOFT_SCAN_TRIGGER", "START_SCANNING")
            )
            Log.i(TAG, "Leitor de código de barras ligado pelo botão (SOFT_SCAN_TRIGGER)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Não foi possível ligar o leitor de código de barras", e)
            false
        }
    }

    private const val PACOTE_DATAWEDGE = "com.symbol.datawedge"

    private fun plugin(nome: String, vararg parametros: Pair<String, String>) = Bundle().apply {
        putString("PLUGIN_NAME", nome)
        putString("RESET_CONFIG", "false")
        putBundle("PARAM_LIST", Bundle().apply { parametros.forEach { (k, v) -> putString(k, v) } })
    }

    /**
     * Recebe os códigos lidos enquanto a [activity] está na frente. [aoLer] devolve true se a tela tratou o código;
     * false = comportamento de teclado: o código vai para o campo com foco (como antes do perfil do DataWedge).
     */
    fun ouvir(activity: AppCompatActivity, aoLer: (String) -> Boolean) {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            private val receptor = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val codigo = intent.getStringExtra(EXTRA_DADOS)?.trim()?.takeIf { it.isNotEmpty() } ?: return
                    // Diálogo aberto (PIN, Finalizar, ficha...): a janela da tela não tem o foco e o código iria para o
                    // campo escondido atrás dele, ou dispararia uma ação por cima. Ignora
                    if (!activity.hasWindowFocus()) {
                        Log.i(TAG, "Código de barras ignorado em ${activity.javaClass.simpleName}: há um diálogo aberto")
                        return
                    }
                    Log.i(TAG, "Código de barras lido em ${activity.javaClass.simpleName}: ${paraLog(codigo)}")
                    if (!aoLer(codigo)) noCampoComFoco(activity, codigo)
                }
            }

            override fun onResume(owner: LifecycleOwner) {
                ContextCompat.registerReceiver(activity, receptor, IntentFilter(acao(activity)), ContextCompat.RECEIVER_EXPORTED)
            }

            override fun onPause(owner: LifecycleOwner) {
                try {
                    activity.unregisterReceiver(receptor)
                } catch (e: IllegalArgumentException) {
                    // Não estava registrado (onResume não chegou a rodar): nada a fazer
                }
            }
        })
    }

    /**
     * O que do código lido pode ir para o log. O QR de configuração (ou só a chave, lida nas Configurações) leva a chave
     * do coletor: no log fica só o endereço do servidor, ou o tamanho. Patrimônio e local aparecem (até 40 caracteres).
     */
    internal fun paraLog(codigo: String): String {
        val partes = codigo.split('|')
        if (partes.size >= 3 && partes[0].startsWith("RFIDCFG")) return "QR de configuração (${partes[0]}|${partes[1]}|…)"
        if (pareceChave(codigo)) return "${codigo.length} caracteres (pode ser a chave do coletor; não registrado)"
        return codigo.take(40)
    }

    // Chave do coletor: um bloco só, sem espaço, com letras e números misturados (patrimônio costuma ser só número
    // ou ter separador)
    private fun pareceChave(codigo: String) =
        codigo.length >= 8 && codigo.none { it.isWhitespace() || it == '/' || it == '-' || it == '.' } &&
            codigo.any { it.isLetter() } && codigo.any { it.isDigit() } && codigo.any { it.isLowerCase() }

    /** Como o teclado fazia: escreve o código no campo com foco (substitui o texto) e deixa o cursor no fim. */
    fun noCampoComFoco(activity: AppCompatActivity, codigo: String): Boolean {
        val campo = activity.currentFocus as? EditText ?: return false
        if (campo.inputType and EditorInfo.TYPE_TEXT_VARIATION_PASSWORD == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD) return false
        campo.setText(codigo)
        campo.setSelection(campo.text.length)
        return true
    }
}
