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
                    Log.i(TAG, "Código de barras lido em ${activity.javaClass.simpleName}: ${codigo.take(40)}")
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

    /** Como o teclado fazia: escreve o código no campo com foco (substitui o texto) e deixa o cursor no fim. */
    fun noCampoComFoco(activity: AppCompatActivity, codigo: String): Boolean {
        val campo = activity.currentFocus as? EditText ?: return false
        if (campo.inputType and EditorInfo.TYPE_TEXT_VARIATION_PASSWORD == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD) return false
        campo.setText(codigo)
        campo.setSelection(campo.text.length)
        return true
    }
}
