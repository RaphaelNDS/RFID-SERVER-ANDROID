package com.example.rfid_server.activities

import com.example.rfid_server.CodigoDeBarras
import com.example.rfid_server.ConfigQr
import com.example.rfid_server.api.RelogioServidor
import com.example.rfid_server.erros.MascaraSensivel
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
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
import com.example.rfid_server.dados.RepositorioInventario
import com.example.rfid_server.dados.RepositorioLeituras
import com.example.rfid_server.fragment.TitulosAbas
import com.example.rfid_server.sincronizacao.EstadoSincronizacao
import com.example.rfid_server.sincronizacao.SincronizacaoWorker
import com.example.rfid_server.sincronizacao.Sincronizador
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var rfidManager: RfidManager
    private lateinit var repositorio: RepositorioLeituras
    private lateinit var sincronizador: Sincronizador
    private lateinit var viewModel: MainViewModel

    private lateinit var pager: ViewPager2
    private lateinit var faixaSincronizacao: View
    private lateinit var txtSincronizacao: TextView
    private lateinit var btnEnviarAgora: View
    private lateinit var tabs: TabLayout

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
    private var inventarioEmAndamento: String? = null

    // A leitura vai direto para o banco local; o Sincronizador cuida do servidor. Tag nova: bip/vibração
    private val ouvinte = RfidManager.OuvinteLeitura { epc, _ -> repositorio.registrar(epc) { aviso.tagNova() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sessao = exigirSessao() ?: return
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        // Nome da função, como as outras telas ("Inventário por local", "Localizar patrimônio"): "RFID-Android" não cabia
        // ao lado do "Nova leitura" na tela de 320dp e aparecia cortado ("RFID-Androi…"; visto nas fotos do coletor)
        title = getString(R.string.main_titulo)
        mostrarOperador(sessao)

        pager = findViewById(R.id.pager)
        faixaSincronizacao = findViewById(R.id.faixaSincronizacao)
        txtSincronizacao = findViewById(R.id.txtSincronizacao)
        btnEnviarAgora = findViewById(R.id.btnEnviarAgora)
        tabs = findViewById(R.id.tabs)

        pager.adapter = MainPagerAdapter(this)

        TabLayoutMediator(tabs, pager) { tab, pos -> tab.text = tituloAba(pos) }.attach()

        repositorio = RepositorioLeituras.get(this)
        sincronizador = Sincronizador.get(this)
        viewModel = ViewModelProvider(this).get(MainViewModel::class.java)
        rfidManager = RfidManager.get(this)
        aviso = AvisoLeitura(this)
        mostrarEstadoLeitor(rfidManager, mostrarFiltros = true)

        // Visível: leituras vêm para cá, envia o pendente a cada minuto e avisa antes de a sessão acabar
        // (deixa renovar sem sair da tela). Ao pausar, solta o leitor e para o envio periódico
        TelaDeLeitura(
            this, rfidManager, ouvinte,
            aoMudarSessao = { mostrarOperador(it) },
            aoRetomar = {
                sincronizador.solicitar()
                handler.postDelayed(sincronizacaoPeriodica, INTERVALO_SINCRONIZACAO_MS)
            },
            aoPausar = { handler.removeCallbacks(sincronizacaoPeriodica) }
        )

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
        RelogioServidor.desvio.observe(this) { atualizarAvisoSincronizacao() }
        viewModel.contagem.observe(this) { mostrarContadores(it) }
        // Recusadas aparecem só como número: tocar no selo mostra quais são, o motivo e "Tentar de novo"
        findViewById<View>(R.id.seloRecusadas).setOnClickListener { mostrarRecusadas() }

        // Títulos das abas com a quantidade de cada lista ("Leitura (12)"), atualizados quando a lista muda
        viewModel.leituras.observe(this) { atualizarAba(TitulosAbas.ABA_LEITURA) }
        viewModel.naoCadastradas.observe(this) { atualizarAba(TitulosAbas.ABA_NAO_CADASTRADAS) }

        // Rodapé: as mesmas ações que antes eram ícones na barra
        findViewById<View>(R.id.btnInventario).setOnClickListener { abrirInventario() }
        findViewById<View>(R.id.btnLocalizar).setOnClickListener { abrirLocalizar() }

        // Faixa de sincronização: o botão e a faixa inteira (alvo grande, para luva) pedem o envio na hora
        btnEnviarAgora.setOnClickListener { enviarAgora() }
        faixaSincronizacao.setOnClickListener { if (btnEnviarAgora.isVisible) enviarAgora() }

        val inventarios = RepositorioInventario.get(this)
        FaixaRecusa.ligar(this, inventarios)
        // Guardado para o Sair avisar que há um inventário em andamento
        inventarios.inventarioAberto.observe(this) { inventarioEmAndamento = it?.local }

        limitarBlocoDeAvisos()

        // Busca: filtra as duas abas enquanto o operador digita (a lista e o título da aba mostram só o que bate)
        findViewById<android.widget.EditText>(R.id.edtBusca).doAfterTextChanged { viewModel.termoBusca.value = it?.toString().orEmpty() }
        // Voltar com a busca aberta fecha a busca (e mostra a leitura inteira de novo) em vez de sair da tela
        onBackPressedDispatcher.addCallback(this, voltarFechaBusca)
        // Embaixo do campo: "Mostrando 3 de 40 etiqueta(s)" (a lista filtrada parecia leitura que não pegou)
        viewModel.contagemBusca.observe(this) { c ->
            findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.campoBusca).helperText =
                c?.let { getString(R.string.main_buscar_contagem, it.first, it.second) }
        }

        // Plaqueta de patrimônio lida na tela principal: abre o Localizar já buscando o bem
        CodigoDeBarras.ouvir(this) { codigo ->
            if (ConfigQr.ler(codigo) != null) return@ouvir true // QR de configuração: só nas Configurações
            // Gatilho RFID apertado (se o leitor de código de barras disparar junto) ou leitura repetida: não abre de novo
            // Busca aberta: a plaqueta vai para a busca (acha o bem na leitura atual) em vez de abrir o Localizar
            if (findViewById<View>(R.id.campoBusca).isVisible) {
                findViewById<android.widget.EditText>(R.id.edtBusca).setText(codigo)
                return@ouvir true
            }
            if (rfidManager.gatilhoApertado.value == true || !CliqueUnico.permitir()) return@ouvir true
            Log.i(TAG, "Código de barras na tela principal: abrindo o Localizar do patrimônio $codigo")
            startActivity(Intent(this, LocalizarActivity::class.java).putExtra(LocalizarActivity.EXTRA_PATRIMONIO, codigo))
            true
        }
    }

    /** Título da aba com a quantidade da lista ("Leitura (12)"); só o rótulo enquanto a lista não carregou. */
    private fun tituloAba(posicao: Int): String {
        // O TabLayoutMediator monta as abas antes de o ViewModel existir: aí vai só o rótulo
        if (!::viewModel.isInitialized) {
            return getString(if (posicao == TitulosAbas.ABA_LEITURA) R.string.main_aba_leitura else R.string.main_aba_nao_cadastradas)
        }
        val (rotulo, quantidade) = if (posicao == TitulosAbas.ABA_LEITURA) R.string.main_aba_leitura to viewModel.leituras.value?.size
            else R.string.main_aba_nao_cadastradas to viewModel.naoCadastradas.value?.size
        return TitulosAbas.titulo(getString(rotulo), quantidade)
    }

    private fun atualizarAba(posicao: Int) {
        tabs.getTabAt(posicao)?.text = tituloAba(posicao)
    }

    /** Fileira de contadores (número grande, rótulo pequeno) e os selos "Aguardando" e "Recusadas", só quando há. */
    private fun mostrarContadores(contagem: ContagemSessao) {
        findViewById<TextView>(R.id.txtNumLidas).text = contagem.lidas.toString()
        findViewById<TextView>(R.id.txtNumCadastradas).text = contagem.cadastradas.toString()
        findViewById<TextView>(R.id.txtNumNaoCadastradas).text = contagem.naoCadastradas.toString()
        // O leitor de tela lê a fileira de uma vez: "Lidas: 12 • Cadastradas: 10 • ..."
        findViewById<View>(R.id.linhaContadores).contentDescription = contagem.resumo()

        val seloAguardando = findViewById<TextView>(R.id.seloAguardando)
        seloAguardando.isVisible = contagem.mostrarSeloAguardando()
        seloAguardando.text = getString(R.string.main_selo_aguardando, contagem.aguardandoServidor)

        val seloRecusadas = findViewById<TextView>(R.id.seloRecusadas)
        seloRecusadas.isVisible = contagem.mostrarSeloRecusadas()
        seloRecusadas.text = getString(R.string.main_selo_recusadas, contagem.recusadas)
        seloRecusadas.contentDescription = getString(R.string.main_selo_recusadas_descricao, contagem.recusadas)

        findViewById<View>(R.id.colunaSelos).isVisible = seloAguardando.isVisible || seloRecusadas.isVisible
    }

    private fun abrirInventario() {
        if (!CliqueUnico.permitir()) return
        startActivity(Intent(this, InventarioActivity::class.java))
    }

    private fun abrirLocalizar() {
        if (!CliqueUnico.permitir()) return
        startActivity(Intent(this, LocalizarActivity::class.java))
    }

    /** Lista das leituras recusadas de vez ainda guardadas (da sessão e de sessões anteriores, até 30 dias). */
    private fun mostrarRecusadas() {
        repositorio.recusadas { recusadas ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (recusadas.isEmpty()) {
                    Toast.makeText(this, R.string.recusadas_nenhuma, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }

                val semMotivo = getString(R.string.recusadas_sem_motivo)
                val mensagem = buildString {
                    recusadas.forEach { append(getString(R.string.recusadas_item, it.epc, it.ultimoErro ?: semMotivo)).append("\n\n") }
                    append(getString(R.string.recusadas_rodape))
                }

                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.recusadas_titulo, recusadas.size))
                    .setMessage(mensagem)
                    .setPositiveButton(R.string.recusadas_tentar_de_novo) { _, _ ->
                        repositorio.tentarDeNovo(recusadas.map { it.id }) { total ->
                            runOnUiThread { Toast.makeText(this, getString(R.string.recusadas_voltaram, total), Toast.LENGTH_LONG).show() }
                        }
                    }
                    .setNegativeButton(R.string.recusadas_fechar, null)
                    .show()
            }
        }
    }

    /** Operador e até quando vale a sessão, no subtítulo da barra do topo (a linha própria tirava altura da lista). */
    private fun mostrarOperador(sessao: com.example.rfid_server.SessaoOperador) {
        // Primeiro nome: com o nome completo o subtítulo cortava ("Até 13:16 · Oper…" nas fotos do coletor)
        val nome = sessao.nome.trim().substringBefore(" ").ifEmpty { sessao.nome }
        supportActionBar?.subtitle = getString(R.string.main_operador, nome, AvisoSessao.horario(sessao.expiraEm))
    }

    /**
     * Bloco de avisos (recusa, contadores, sincronização) com a altura natural enquanto couber; se não couber,
     * encolhe e rola para a lista ficar com pelo menos [AlturaTopoPrincipal.MINIMO_LISTA_DP]. Refeito a cada
     * mudança de layout (aviso que aparece ou some, faixa do leitor com "Reconectar"...).
     */
    private fun limitarBlocoDeAvisos() {
        val raiz = findViewById<ViewGroup>(R.id.raizPrincipal)
        val rolagem = findViewById<View>(R.id.rolagemTopo)
        val conteudo = findViewById<View>(R.id.conteudoTopo)
        val minimoLista = (AlturaTopoPrincipal.MINIMO_LISTA_DP * resources.displayMetrics.density).roundToInt()

        val ajustar = Runnable {
            // O que a tela tem para o bloco e a lista juntos: tudo menos os outros itens da coluna
            val outros = (0 until raiz.childCount).map { raiz.getChildAt(it) }
                .filter { it !== rolagem && it !== pager && it.visibility != View.GONE }
                .sumOf { it.height }
            val blocoMaisLista = raiz.height - raiz.paddingTop - raiz.paddingBottom - outros
            val altura = AlturaTopoPrincipal.limite(conteudo.height, blocoMaisLista, minimoLista) ?: ViewGroup.LayoutParams.WRAP_CONTENT
            // Só mexe quando muda: senão cada layout pediria outro
            if (rolagem.layoutParams.height != altura) {
                rolagem.layoutParams = rolagem.layoutParams.apply { height = altura }
            }
        }
        // Fora do layout em andamento (pedir layout no meio dele não vale)
        val aoMudar = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            rolagem.removeCallbacks(ajustar)
            rolagem.post(ajustar)
        }
        raiz.addOnLayoutChangeListener(aoMudar)
        conteudo.addOnLayoutChangeListener(aoMudar)
    }

    private fun atualizarAvisoSincronizacao() {
        val pendentes = viewModel.pendentes.value ?: 0
        // Relógio do coletor muito diferente do servidor: o servidor recusa as leituras pela hora, então avisa sempre
        // (versão curta, para caber na faixa junto com as pendências: "Relógio 7 min adiantado: acerte...")
        val avisoRelogio = RelogioServidor.desvio.value
            ?.takeIf { RelogioServidor.aviso(it) != null }
            ?.let { getString(R.string.main_aviso_relogio, RelogioServidor.descrever(it)) }

        if (pendentes == 0 && avisoRelogio == null) {
            faixaSincronizacao.isVisible = false
            return
        }

        val estado = viewModel.estadoSincronizacao.value
        val acoes = RegrasFaixaSincronizacao.acoes(pendentes, estado)

        val avisoPendentes = if (pendentes == 0) null else {
            val motivo = if (estado == null || estado == EstadoSincronizacao.SINCRONIZADO) getString(R.string.main_enviando) else estado.descricao
            getString(R.string.main_aviso_sincronizacao, motivo, pendentes)
        }
        // Problema que só se resolve nas Configurações (com PIN): o operador não tem o que fazer sozinho
        val avisoResponsavel = if (acoes.chamarResponsavel) getString(R.string.main_chame_responsavel) else null

        txtSincronizacao.text = RegrasFaixaSincronizacao.texto(avisoRelogio, avisoPendentes, avisoResponsavel)
        btnEnviarAgora.isVisible = acoes.enviarAgora
        faixaSincronizacao.isClickable = acoes.enviarAgora
        faixaSincronizacao.isVisible = true
    }

    private fun enviarAgora() {
        Log.i(TAG, "Operador pediu o envio imediato das leituras guardadas (${viewModel.pendentes.value ?: 0} aguardando)")
        sincronizador.solicitar()
        Toast.makeText(this, R.string.main_enviando_agora, Toast.LENGTH_SHORT).show()
    }

    // ================= BUSCA E PLANILHA DA LEITURA =================

    private val voltarFechaBusca = object : androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = alternarBusca()
    }

    /** Mostra o campo de busca (com o teclado) ou, se já aberto, limpa e esconde. */
    private fun alternarBusca() {
        val campo = findViewById<View>(R.id.campoBusca)
        val edt = findViewById<android.widget.EditText>(R.id.edtBusca)
        if (campo.isVisible) {
            edt.setText("")
            campo.isVisible = false
            voltarFechaBusca.isEnabled = false
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(edt.windowToken, 0)
        } else {
            campo.isVisible = true
            voltarFechaBusca.isEnabled = true
            edt.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(edt, 0)
        }
    }

    /** Planilha (CSV) de toda a leitura atual, para mandar por e-mail ou WhatsApp. */
    private fun compartilharPlanilha() {
        val csv = viewModel.planilha()
        if (csv.lines().count { it.isNotBlank() } <= 1) {
            Toast.makeText(this, R.string.main_planilha_vazia, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val pasta = java.io.File(cacheDir, "relatorios").apply { mkdirs() }
            pasta.listFiles()?.forEach { it.delete() } // só a última planilha fica guardada
            val arquivo = java.io.File(pasta, "leituras-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date()) + ".csv")
            // Marca de UTF-8 no início: o Excel reconhece os acentos
            arquivo.writeText(Char(0xFEFF).toString() + csv, Charsets.UTF_8)
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.atualizacao", arquivo)
            val envio = Intent(Intent.ACTION_SEND).setType("text/csv")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.main_planilha))
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(envio, getString(R.string.main_planilha)))
            Log.i(TAG, "Operador compartilhou a planilha da leitura (${csv.lines().count { it.isNotBlank() } - 1} etiqueta(s))")
        } catch (e: Exception) {
            Log.w(TAG, "Planilha da leitura não compartilhada", e)
            Toast.makeText(this, R.string.inventario_compartilhar_indisponivel, Toast.LENGTH_LONG).show()
        }
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

            R.id.menu_buscar -> {
                alternarBusca()
                true
            }

            R.id.menu_planilha -> {
                compartilharPlanilha()
                true
            }

            R.id.menu_recusadas -> {
                mostrarRecusadas()
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
            append(getString(R.string.main_sair_mensagem, sessao.nome))
            if (pendentes > 0) {
                append("\n\n").append(getString(R.string.main_sair_pendentes, pendentes))
            }
            inventarioEmAndamento?.let { local ->
                append("\n\n").append(getString(R.string.main_sair_inventario, local))
            }
        }
        inventarioEmAndamento?.let { Log.i(TAG, "Operador pediu para sair com o inventário de '$it' em andamento: avisado") }

        AlertDialog.Builder(this)
            .setTitle(R.string.main_sair_titulo)
            .setMessage(mensagem)
            .setPositiveButton(R.string.main_sair) { _, _ -> sair(sessao.token, sessao.email) }
            .setNegativeButton(R.string.comum_cancelar, null)
            .show()
    }

    private fun sair(token: String, email: String) {
        Log.i(TAG, "Operador ${MascaraSensivel.email(email)} saiu do coletor")
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
            Toast.makeText(this, R.string.main_tela_vazia, Toast.LENGTH_SHORT).show()
            return
        }

        val pendentes = viewModel.pendentes.value ?: 0
        val mensagem = buildString {
            append(getString(R.string.main_nova_leitura_mensagem, contagem.lidas))
            if (pendentes > 0) {
                append("\n\n").append(getString(R.string.main_nova_leitura_pendentes, pendentes))
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.main_nova_leitura_titulo)
            .setMessage(mensagem)
            .setPositiveButton(R.string.main_nova_leitura) { _, _ ->
                Log.i(TAG, "Operador iniciou nova leitura (${contagem.lidas} tag(s) na tela, $pendentes aguardando envio)")
                viewModel.novaLeitura()
            }
            .setNegativeButton(R.string.comum_cancelar) { _, _ ->
                Log.d(TAG, "Nova leitura cancelada pelo operador")
            }
            .show()
    }

    private fun abrirConfiguracoes() {
        if (!CliqueUnico.permitir()) return
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
