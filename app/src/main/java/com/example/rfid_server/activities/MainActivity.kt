package com.example.rfid_server.activities

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.ViewModelProvider
import androidx.viewpager2.widget.ViewPager2
import com.example.rfid_server.AppConfig
import com.example.rfid_server.AvisoLeitura
import com.example.rfid_server.BuildConfig
import com.example.rfid_server.MainViewModel
import com.example.rfid_server.R
import com.example.rfid_server.RfidManager
import com.example.rfid_server.SessaoAtual
import com.example.rfid_server.service.TagService
import com.example.rfid_server.adapter.MainPagerAdapter
import com.example.rfid_server.dados.ContagemSessao
import com.example.rfid_server.dados.RepositorioLeituras
import com.example.rfid_server.sincronizacao.EstadoSincronizacao
import com.example.rfid_server.sincronizacao.SincronizacaoWorker
import com.example.rfid_server.sincronizacao.Sincronizador
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    private lateinit var rfidManager: RfidManager
    private lateinit var repositorio: RepositorioLeituras
    private lateinit var sincronizador: Sincronizador
    private lateinit var viewModel: MainViewModel

    private lateinit var pager: ViewPager2
    private lateinit var txtSincronizacao: TextView
    private lateinit var txtContadores: TextView

    // Com o app aberto, tenta enviar o que estiver pendente a cada minuto
    private val handler = Handler(Looper.getMainLooper())
    private val sincronizacaoPeriodica = object : Runnable {
        override fun run() {
            // A sessão pode expirar com a tela aberta (fim do turno): volta para o login
            exigirSessao() ?: return
            sincronizador.solicitar()
            handler.postDelayed(this, INTERVALO_SINCRONIZACAO_MS)
        }
    }

    private lateinit var aviso: AvisoLeitura

    // A leitura vai direto para o banco local; o Sincronizador cuida do servidor. Tag nova: bip/vibração
    // Avisa antes de a sessão acabar e deixa renovar sem sair da tela
    private val avisoSessao = AvisoSessao(this) { mostrarOperador(it) }

    private val ouvinte = RfidManager.OuvinteLeitura { epc, _ -> repositorio.registrar(epc) { aviso.tagNova() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessao = exigirSessao() ?: return
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        title = getString(R.string.app_name)
        mostrarOperador(sessao)

        pager = findViewById(R.id.pager)
        txtSincronizacao = findViewById(R.id.txtSincronizacao)
        txtContadores = findViewById(R.id.txtContadores)
        val tabs = findViewById<TabLayout>(R.id.tabs)

        pager.adapter = MainPagerAdapter(this)

        TabLayoutMediator(tabs, pager) { tab, pos ->
            tab.text = if (pos == 0) "Leitura" else "Não cadastradas"
        }.attach()

        repositorio = RepositorioLeituras.get(this)
        sincronizador = Sincronizador.get(this)
        viewModel = ViewModelProvider(this).get(MainViewModel::class.java)
        rfidManager = RfidManager.get(this)
        aviso = AvisoLeitura(this)
        mostrarEstadoLeitor(rfidManager)

        // App aberto do zero: tela limpa, como antes. (Ao recriar por rotação, mantém a sessão.)
        // O que não foi enviado continua na fila.
        if (savedInstanceState == null) {
            // Primeira linha útil do log ao investigar um problema: qual versão estava no coletor
            Log.i(
                TAG,
                "${getString(R.string.app_name)} ${BuildConfig.VERSION_NAME} (código ${BuildConfig.VERSION_CODE}, " +
                    "build ${BuildConfig.BUILD_TYPE}) iniciado — app ${BuildConfig.APPLICATION_ID}"
            )
            repositorio.iniciarNovaSessao("app aberto")
        }

        SincronizacaoWorker.agendarPeriodica(this)

        viewModel.pendentes.observe(this) { atualizarAvisoSincronizacao() }
        viewModel.estadoSincronizacao.observe(this) { atualizarAvisoSincronizacao() }
        viewModel.contagem.observe(this) { txtContadores.text = it.resumo() }
    }

    override fun onResume() {
        super.onResume()
        exigirSessao() ?: return

        rfidManager.definirOuvinte(this, ouvinte)   // conecta o leitor, se preciso, e reconecta sozinho se cair

        sincronizador.solicitar()
        handler.postDelayed(sincronizacaoPeriodica, INTERVALO_SINCRONIZACAO_MS)
        avisoSessao.iniciar()
    }

    override fun onPause() {
        super.onPause()
        rfidManager.removerOuvinte(this)
        handler.removeCallbacks(sincronizacaoPeriodica)
        avisoSessao.parar()
    }

    private fun mostrarOperador(sessao: com.example.rfid_server.SessaoOperador) {
        findViewById<TextView>(R.id.txtSessao).text = "Operador: ${sessao.nome} · sessão até ${AvisoSessao.horario(sessao.expiraEm)}"
    }

    private fun atualizarAvisoSincronizacao() {
        val pendentes = viewModel.pendentes.value ?: 0

        if (pendentes == 0) {
            txtSincronizacao.visibility = View.GONE
            return
        }

        val estado = viewModel.estadoSincronizacao.value
        val motivo = if (estado == null || estado == EstadoSincronizacao.SINCRONIZADO) "Enviando" else estado.descricao

        txtSincronizacao.text = "$motivo — $pendentes leitura(s) guardada(s) no coletor, aguardando envio"
        txtSincronizacao.visibility = View.VISIBLE
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {

            R.id.menu_nova_leitura -> {
                confirmarNovaLeitura()
                true
            }

            R.id.menu_localizar -> {
                startActivity(Intent(this, LocalizarActivity::class.java))
                true
            }

            R.id.menu_inventario -> {
                startActivity(Intent(this, InventarioActivity::class.java))
                true
            }

            R.id.menu_settings -> {
                abrirConfiguracoes()
                true
            }

            R.id.menu_sair -> {
                confirmarSaida()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmarSaida() {
        val sessao = SessaoAtual.get(this) ?: return run { exigirSessao() }
        val pendentes = viewModel.pendentes.value ?: 0

        val mensagem = buildString {
            append("Sair como ${sessao.nome}? O próximo operador entra com o próprio usuário.")
            if (pendentes > 0) {
                append("\n\n$pendentes registro(s) ainda não chegaram ao servidor: continuam guardados no coletor e serão enviados com o seu nome.")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Sair do coletor")
            .setMessage(mensagem)
            .setPositiveButton("Sair") { _, _ -> sair(sessao.token, sessao.email) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun sair(token: String, email: String) {
        Log.i(TAG, "Operador $email saiu do coletor")
        rfidManager.removerOuvinte(this)
        TagService(this).sair(token)   // sem rede, a sessão expira sozinha no servidor
        AppConfig.limparSessao(this)

        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    private fun confirmarNovaLeitura() {
        val contagem = viewModel.contagem.value ?: ContagemSessao()

        if (contagem.lidas == 0) {
            Toast.makeText(this, "A tela já está vazia", Toast.LENGTH_SHORT).show()
            return
        }

        val pendentes = viewModel.pendentes.value ?: 0
        val mensagem = buildString {
            append("As ${contagem.lidas} tag(s) lidas vão sair da tela, e a mesma tag poderá ser lida de novo.")
            if (pendentes > 0) {
                append("\n\n$pendentes leitura(s) ainda não chegaram ao servidor: elas continuam guardadas no coletor e serão enviadas assim que possível.")
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Começar nova leitura?")
            .setMessage(mensagem)
            .setPositiveButton("Nova leitura") { _, _ ->
                Log.i(TAG, "Operador iniciou nova leitura (${contagem.lidas} tag(s) na tela, $pendentes aguardando envio)")
                viewModel.novaLeitura()
            }
            .setNegativeButton("Cancelar") { _, _ ->
                Log.d(TAG, "Nova leitura cancelada pelo operador")
            }
            .show()
    }

    private fun abrirConfiguracoes() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::aviso.isInitialized) aviso.liberar()

        // Só solta o leitor quando o app está saindo de verdade (não numa rotação de tela).
        // Sessão vencida no onCreate: exigirSessao() já fechou a tela antes de o leitor ser criado
        if (isFinishing && ::rfidManager.isInitialized) {
            rfidManager.desconectar()
        }
    }

    companion object {
        private const val TAG = "MAIN"
        private const val INTERVALO_SINCRONIZACAO_MS = 60_000L
    }
}
