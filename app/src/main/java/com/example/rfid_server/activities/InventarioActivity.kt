package com.example.rfid_server.activities

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.CodigoDeBarras
import com.example.rfid_server.CofreSessoes
import com.example.rfid_server.sincronizacao.FilaJustificativas
import com.example.rfid_server.sincronizacao.SincronizadorJustificativas
import com.example.rfid_server.ConfigQr
import com.example.rfid_server.AppConfig
import com.example.rfid_server.AvisoLeitura
import com.example.rfid_server.R
import com.example.rfid_server.RegrasLocalizar
import com.example.rfid_server.RfidManager
import com.example.rfid_server.SessaoAtual
import com.example.rfid_server.adapter.EsperadoAdapter
import com.example.rfid_server.adapter.LidaInventarioAdapter
import com.example.rfid_server.dados.ComparacaoInventario
import com.example.rfid_server.dados.EsperadoLocal
import com.example.rfid_server.dados.InventarioEntity
import com.example.rfid_server.dados.ItemEsperado
import com.example.rfid_server.dados.LidaInventario
import com.example.rfid_server.dados.RepositorioInventario
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService
import com.example.rfid_server.sincronizacao.EstadoSincronizacao
import com.example.rfid_server.sincronizacao.Sincronizador
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Inventário por local: o operador escolhe o local, inicia, lê as etiquetas com o gatilho e finaliza.
 * Tudo fica guardado no coletor e é enviado ao servidor quando houver rede (Sincronizador).
 * Se o app fechar no meio, o inventário continua aberto e é retomado aqui.
 *
 * Se o local já teve inventário finalizado, a tela mostra ao vivo o que falta, comparando com o que foi
 * encontrado da última vez (GET /api/android/inventarios/esperado). Tocar num item que falta abre o
 * Localizar já com o EPC dele (sem depender do servidor). A lista fica guardada no coletor e vale sem rede.
 */
class InventarioActivity : AppCompatActivity() {

    private lateinit var repositorio: RepositorioInventario
    private lateinit var rfidManager: RfidManager
    private lateinit var tagService: TagService

    private lateinit var painelInicio: View
    private lateinit var painelAndamento: LinearLayout
    private lateinit var edtLocal: AutoCompleteTextView
    // Moldura do campo do local: mostra o erro de local grande embaixo do campo
    private lateinit var campoLocal: TextInputLayout
    // Barra do topo: seta de voltar, local no subtítulo e "Cancelar inventário" no menu
    private lateinit var barra: MaterialToolbar
    private lateinit var txtResumo: TextView
    private lateinit var txtReferencia: TextView
    private lateinit var txtRecusado: TextView
    private lateinit var txtStatus: TextView
    private lateinit var tabs: TabLayout
    private lateinit var recycler: RecyclerView
    private lateinit var adapterLidas: LidaInventarioAdapter
    private lateinit var adapterFaltando: EsperadoAdapter
    private lateinit var campoFiltro: View
    private lateinit var edtFiltro: EditText
    private lateinit var linhaEnvio: View
    private lateinit var btnEnviarAgora: Button
    private lateinit var txtAguardando: TextView
    private lateinit var txtChameResponsavel: TextView

    private var inventarioAtual: InventarioEntity? = null
    private var itensObservados: LiveData<List<String>>? = null
    private var naoEnviadosObservados: LiveData<Int>? = null
    private var epcsLidos: List<String> = emptyList()
    private var naoEnviadas = 0
    private var estadoSincronizacao: EstadoSincronizacao? = null

    private var esperado: EsperadoLocal? = null

    // Toque duplo (ou Enter do teclado junto com o toque): o painel só troca quando o banco avisa do inventário
    // aberto, e um segundo Iniciar nesse meio-tempo mostrava "Já existe um inventário em andamento"
    private var abrindo = false
    // Uma pergunta de Finalizar por vez: a segunda ficava aberta por baixo do resumo
    private var dialogoFinalizar: AlertDialog? = null

    // EPC → patrimônio do esperado: montado uma vez quando a lista chega, e não a cada etiqueta lida
    @Volatile // lido também na thread do leitor (som de etiqueta fora da lista)
    private var patrimoniosEsperados: Map<String, String?> = emptyMap()
    @Volatile
    private var temListaEsperada = false

    // Último cálculo aplicado na tela (comparação e listas já filtradas)
    private var situacao: SituacaoTelaInventario? = null
    private val comparacao: ComparacaoInventario? get() = situacao?.comparacao
    private var termoFiltro = ""
    private var avisouCompleto = false

    private lateinit var aviso: AvisoLeitura

    // Lê e grava a lista do esperado guardada no coletor fora da thread principal: com milhares de etiquetas,
    // ou com a memória cheia (a gravação falha), a tela travava ou fechava. Também calcula a comparação
    private val arquivos = Executors.newSingleThreadExecutor()

    // Comparação agrupada: no máximo um cálculo a cada RitmoComparacao.INTERVALO_MS, e um de cada vez.
    // Só a thread principal mexe nestes campos
    private val principal = Handler(Looper.getMainLooper())
    private val rodarComparacao = Runnable { calcularComparacao() }
    private var calculoAgendado = false
    private var calculando = false
    private var recalcularDepois = false
    private var ultimoCalculo: Long? = null

    // Muda quando o inventário sai da tela: cálculo que termina depois disso é descartado
    private var geracao = 0

    // Etiqueta nova no inventário: bip/vibração
    // Som pela situação: com a lista do esperado, etiqueta fora dela dá bip duplo (o operador percebe sem olhar a tela)
    private val ouvinte = object : RfidManager.OuvinteLeitura {
        override fun aoLer(epc: String, rssi: Int) {
            repositorio.registrar(epc) {
                val fora = temListaEsperada && epc !in patrimoniosEsperados
                aviso.tagNova(if (fora) AvisoLeitura.Tipo.FORA_DA_LISTA else AvisoLeitura.Tipo.NORMAL)
            }
        }

        // "Só etiquetas da empresa" (Configurações) vale aqui: no inventário crachás e embalagens só atrapalham
        override val usaFiltroEmpresa: Boolean get() = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sessão vencida: vai para o login já aqui. Esperar o onResume não basta: no onStart o inventário
        // aberto já ligaria o leitor e consultaria o servidor sem sessão
        exigirSessao() ?: return
        setContentView(R.layout.activity_inventario)

        repositorio = RepositorioInventario.get(this)
        rfidManager = RfidManager.get(this)
        tagService = TagService(this)
        aviso = AvisoLeitura(this)

        // O leitor só manda leituras para cá enquanto há inventário aberto e a tela está visível.
        // Inventário costuma ser longo: avisa antes de a sessão acabar e deixa renovar sem sair da tela.
        // Criada só depois do exigirSessao() acima: se a tela fechou por sessão vencida, nada é registrado
        // (antes o onPause precisava conferir se o rfidManager já existia)
        TelaDeLeitura(this, rfidManager, ouvinte, lerAoRetomar = { inventarioAtual != null })

        painelInicio = findViewById(R.id.painelInicio)
        painelAndamento = findViewById(R.id.painelAndamento)
        edtLocal = findViewById(R.id.edtLocal)
        campoLocal = findViewById(R.id.campoLocal)
        txtResumo = findViewById(R.id.txtResumo)
        txtReferencia = findViewById(R.id.txtReferencia)
        txtRecusado = findViewById(R.id.txtRecusado)
        txtStatus = findViewById(R.id.txtStatus)
        tabs = findViewById(R.id.tabsInventario)
        campoFiltro = findViewById(R.id.campoFiltro)
        edtFiltro = findViewById(R.id.edtFiltro)
        linhaEnvio = findViewById(R.id.linhaEnvio)
        txtAguardando = findViewById(R.id.txtAguardando)
        txtChameResponsavel = findViewById(R.id.txtChameResponsavel)

        adapterLidas = LidaInventarioAdapter { localizarForaDaLista(it) }
        adapterFaltando = EsperadoAdapter { localizar(it) }
        recycler = findViewById<RecyclerView>(R.id.recyclerItens).apply {
            layoutManager = LinearLayoutManager(this@InventarioActivity)
            adapter = adapterLidas
        }

        tabs.addTab(tabs.newTab().setText(R.string.inventario_aba_faltando))
        tabs.addTab(tabs.newTab().setText(R.string.inventario_aba_lidas))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                recycler.adapter = if (tab.position == ABA_FALTANDO) adapterFaltando else adapterLidas
                atualizarCampoFiltro()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        configurarBarra()

        findViewById<Button>(R.id.btnIniciar).setOnClickListener { iniciar() }
        findViewById<Button>(R.id.btnFinalizar).setOnClickListener { confirmarFinalizacao() }
        btnEnviarAgora = findViewById(R.id.btnEnviarAgora)
        btnEnviarAgora.setOnClickListener { enviarAgora() }

        // "Ir" do teclado (e Enter do teclado físico ou do leitor de código de barras) faz o mesmo que "Iniciar inventário"
        edtLocal.setOnEditorActionListener { _, actionId, evento ->
            when (RegrasLocalizar.acaoTecla(actionId, evento?.keyCode, evento?.action, EditorInfo.IME_ACTION_GO)) {
                RegrasLocalizar.AcaoTecla.BUSCAR -> { iniciar(); true }
                RegrasLocalizar.AcaoTecla.CONSUMIR -> true
                RegrasLocalizar.AcaoTecla.IGNORAR -> false
            }
        }
        // Escolheu uma sugestão: o local está pronto, o teclado sai da frente do botão
        edtLocal.setOnItemClickListener { _, _, _, _ -> fecharTeclado(edtLocal) }
        // Etiqueta de código de barras na porta da sala: preenche o local (o operador confere e toca em Iniciar).
        // Com o inventário aberto, o código vai para o campo com foco (ex.: o filtro), como antes
        CodigoDeBarras.ouvir(this) { codigo ->
            if (ConfigQr.ler(codigo) != null) return@ouvir true
            if (inventarioAtual != null) return@ouvir false
            edtLocal.setText(codigo)
            edtLocal.setSelection(edtLocal.text.length)
            edtLocal.dismissDropDown()
            true
        }
        // Tocar no campo vazio mostra todos os locais: antes a lista só aparecia digitando uma letra, e no teste o
        // operador achou que o local "TESTE 171" não existia no coletor
        edtLocal.setOnClickListener { mostrarTodosOsLocais() }
        // Foco pelo listener global da janela: o do próprio campo é usado pela seta do Material (endIconMode dropdown_menu)
        edtLocal.viewTreeObserver.addOnGlobalFocusChangeListener { _, novo -> if (novo === edtLocal) edtLocal.post { mostrarTodosOsLocais() } }
        // O aviso de local grande some quando o operador mexe no nome (volta o exemplo embaixo do campo)
        edtLocal.doAfterTextChanged { if (campoLocal.error != null) campoLocal.error = null }

        // Filtro só em memória: refaz a lista no próximo cálculo (agrupado como as leituras)
        edtFiltro.doAfterTextChanged {
            termoFiltro = it?.toString().orEmpty()
            pedirComparacao()
        }
        // O filtro já vale enquanto digita: "Buscar" do teclado só tira o teclado da frente da lista
        edtFiltro.setOnEditorActionListener { _, actionId, evento ->
            when (RegrasLocalizar.acaoTecla(actionId, evento?.keyCode, evento?.action)) {
                RegrasLocalizar.AcaoTecla.BUSCAR -> { fecharTeclado(edtFiltro); true }
                RegrasLocalizar.AcaoTecla.CONSUMIR -> true
                RegrasLocalizar.AcaoTecla.IGNORAR -> false
            }
        }
        abrirEspacoComTeclado()

        mostrarEstadoLeitor(rfidManager, mostrarFiltros = true, usaFiltroEmpresa = true)
        carregarLocais()

        repositorio.inventarioAberto.observe(this) { mostrar(it) }
        Sincronizador.get(this).estado.observe(this) {
            estadoSincronizacao = it
            atualizarResumo()
        }
        FaixaRecusa.ligar(this, repositorio)
    }

    // Um inventário passou a aparecer na tela (ver mostrar). No onResume quem liga é a TelaDeLeitura.
    // Conecta o leitor, se preciso, e reconecta sozinho se cair; a situação aparece na faixa do topo
    private fun ativarLeitor() {
        rfidManager.definirOuvinte(this, ouvinte)
    }

    /**
     * Seta de voltar e menu da barra. "Cancelar inventário" saiu do cabeçalho (ocupava espaço da lista) e foi para o
     * menu de três pontos, em vermelho por ser destrutiva; a confirmação e a regra continuam as mesmas.
     */
    private fun configurarBarra() {
        barra = findViewById(R.id.barraInventario)
        barra.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
        barra.inflateMenu(R.menu.menu_inventario)
        barra.menu.findItem(R.id.menu_cancelar_inventario)?.let { item ->
            item.title = SpannableString(item.title).apply {
                setSpan(ForegroundColorSpan(ContextCompat.getColor(this@InventarioActivity, R.color.erro)), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        barra.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_cancelar_inventario -> { confirmarCancelamento(); true }
                else -> false
            }
        }
    }

    /**
     * Com o teclado aberto no filtro, em 320x533dp a lista ficava com 0dp (o operador não via o resultado enquanto
     * digitava). Enquanto o teclado estiver na tela, o cabeçalho (resumo, referência, envio) e "Finalizar" saem e
     * a lista fica com o espaço; voltam quando o teclado fecha. A faixa do leitor fica (avisa leitor caído).
     */
    private fun abrirEspacoComTeclado() {
        val cabecalho = findViewById<View>(R.id.cabecalhoAndamento)
        val finalizar = findViewById<View>(R.id.btnFinalizar)
        ViewCompat.setOnApplyWindowInsetsListener(painelAndamento) { _, insets ->
            val semTeclado = !insets.isVisible(WindowInsetsCompat.Type.ime())
            cabecalho.isVisible = semTeclado
            finalizar.isVisible = semTeclado
            insets
        }
    }

    /** Tira o teclado da frente (ele cobria o botão ou a lista) e solta o foco do campo. */
    private fun fecharTeclado(campo: View) {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(campo.windowToken, 0)
        campo.clearFocus()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::aviso.isInitialized) aviso.liberar()
        principal.removeCallbacks(rodarComparacao)
        arquivos.shutdown()
    }

    // ================= TELA =================

    private fun mostrar(inventario: InventarioEntity?) {
        val mudou = inventario?.id != inventarioAtual?.id
        inventarioAtual = inventario

        // "Cancelar inventário" só aparece enquanto o servidor ainda não conhece o inventário (na prática, sem rede):
        // a abertura costuma ser enviada segundos depois de iniciar, e daí em diante o caminho é finalizar. Esta tela
        // é chamada de novo quando a sincronização marca a abertura como enviada, e o item some sozinho
        barra.menu.findItem(R.id.menu_cancelar_inventario)?.isVisible =
            inventario != null && RepositorioInventario.podeCancelar(inventario, etiquetasEnviadas = 0)

        if (inventario == null) {
            painelInicio.visibility = View.VISIBLE
            painelAndamento.visibility = View.GONE
            barra.subtitle = null
            rfidManager.removerOuvinte(this)
            pararDeObservarItens()
            return
        }

        painelInicio.visibility = View.GONE
        painelAndamento.visibility = View.VISIBLE
        // O local vai no subtítulo da barra: uma linha a menos no cabeçalho, sobra para a lista
        barra.subtitle = getString(R.string.inventario_local, inventario.local)
        atualizarResumo()

        if (mudou) {
            observarItens(inventario.id)
            carregarEsperado(inventario.local)
            ativarLeitor()
        }
    }

    private fun observarItens(inventarioId: String) {
        pararDeObservarItens()

        itensObservados = repositorio.epcsLidos(inventarioId).also { liveData ->
            liveData.observe(this) { epcs ->
                epcsLidos = epcs
                pedirComparacao()
            }
        }
        naoEnviadosObservados = repositorio.itensNaoEnviados(inventarioId).also { liveData ->
            liveData.observe(this) { quantidade ->
                naoEnviadas = quantidade
                atualizarResumo()
            }
        }
    }

    private fun pararDeObservarItens() {
        // Antes de tudo: limpar o campo dispara o pedido de cálculo, que é cancelado logo abaixo
        edtFiltro.text?.clear()
        termoFiltro = ""

        itensObservados?.removeObservers(this)
        naoEnviadosObservados?.removeObservers(this)
        itensObservados = null
        naoEnviadosObservados = null
        epcsLidos = emptyList()
        naoEnviadas = 0
        esperado = null
        patrimoniosEsperados = emptyMap()
        temListaEsperada = false
        situacao = null
        avisouCompleto = false

        // Cálculo agendado é cancelado; o que já está rodando termina, mas é descartado (outra geração)
        geracao++
        principal.removeCallbacks(rodarComparacao)
        calculoAgendado = false
        recalcularDepois = false

        adapterLidas.atualizar(emptyList())
        adapterFaltando.atualizar(emptyList())
        tabs.visibility = View.GONE
        campoFiltro.isVisible = false
        txtReferencia.text = ""
    }

    /**
     * Pede o recálculo do que falta (a cada etiqueta lida, quando chega a lista do esperado ou quando o filtro muda).
     * Com milhares de etiquetas, recalcular a cada leitura na thread principal travava a tela: os pedidos são
     * agrupados (no máximo um cálculo a cada RitmoComparacao.INTERVALO_MS) e o cálculo roda em segundo plano.
     */
    private fun pedirComparacao() {
        if (calculando) {
            recalcularDepois = true
            return
        }
        if (calculoAgendado) return

        calculoAgendado = true
        principal.postDelayed(rodarComparacao, RitmoComparacao.espera(SystemClock.elapsedRealtime(), ultimoCalculo))
    }

    private fun calcularComparacao() {
        calculoAgendado = false
        calculando = true
        ultimoCalculo = SystemClock.elapsedRealtime()

        // Fotografia do que a thread principal tem agora (listas e mapa não são alterados depois de montados)
        val geracaoDoCalculo = geracao
        val esperadoAgora = esperado
        val patrimonios = patrimoniosEsperados
        val lidos = epcsLidos
        val termo = termoFiltro

        val aceito = emSegundoPlano {
            val nova = try {
                SituacaoTelaInventario.calcular(esperadoAgora, patrimonios, lidos, termo)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao comparar o inventário com o esperado (${lidos.size} etiqueta(s) lida(s))", e)
                null
            }
            runOnUiThread {
                calculando = false
                if (isDestroyed) return@runOnUiThread
                if (nova != null && geracaoDoCalculo == geracao) aplicarSituacao(nova)
                if (recalcularDepois) {
                    recalcularDepois = false
                    pedirComparacao()
                }
            }
        }
        if (!aceito) calculando = false
    }

    /** Aplica na tela o resultado do cálculo (thread principal). */
    private fun aplicarSituacao(nova: SituacaoTelaInventario) {
        situacao = nova
        adapterLidas.atualizar(nova.lidas)
        adapterFaltando.atualizar(nova.faltando)
        atualizarAbas(nova)
        atualizarCampoFiltro()
        atualizarResumo()
    }

    private fun atualizarAbas(situacaoAtual: SituacaoTelaInventario) {
        val nova = situacaoAtual.comparacao
        if (nova == null) {
            tabs.visibility = View.GONE
            recycler.adapter = adapterLidas
        } else {
            if (tabs.visibility != View.VISIBLE) {
                tabs.visibility = View.VISIBLE
                tabs.getTabAt(ABA_FALTANDO)?.select()
                recycler.adapter = adapterFaltando
            }
            tabs.getTabAt(ABA_FALTANDO)?.text = getString(R.string.inventario_aba_faltando_qtd, nova.faltando.size)
            tabs.getTabAt(ABA_LIDAS)?.text = getString(R.string.inventario_aba_lidas_qtd, situacaoAtual.totalLidas)
            avisarSeCompleto(nova)
        }
    }

    /** Campo de filtro: só com lista grande na aba aberta (ou com texto digitado). */
    private fun atualizarCampoFiltro() {
        val s = situacao
        val tamanho = when {
            s == null -> 0
            recycler.adapter === adapterFaltando -> s.comparacao?.faltando?.size ?: 0
            else -> s.totalLidas
        }
        campoFiltro.isVisible = FiltroInventario.mostrarCampo(tamanho, termoFiltro)
    }

    private fun avisarSeCompleto(nova: ComparacaoInventario) {
        if (nova.esperados == 0 || nova.faltando.isNotEmpty() || avisouCompleto) return
        avisouCompleto = true

        val local = inventarioAtual?.local
        Log.i(TAG, "Todas as ${nova.esperados} etiqueta(s) esperada(s) em $local foram encontradas (${nova.foraDaLista} fora da lista)")
        Toast.makeText(this, getString(R.string.inventario_todas_encontradas, nova.esperados), Toast.LENGTH_LONG).show()
        aviso.concluido()
    }

    private fun atualizarResumo() {
        val c = comparacao
        // Contagem do mesmo cálculo da comparação, para os números baterem entre si
        val totalLidas = situacao?.totalLidas ?: epcsLidos.size
        val recusado = inventarioAtual?.takeIf { it.recusadoEm != null }

        txtResumo.text = buildString {
            append(getString(R.string.inventario_resumo_lidas, totalLidas))
            if (c != null) {
                append(SEPARADOR).append(getString(R.string.inventario_resumo_esperadas, c.encontradas, c.esperados))
                if (c.faltando.isNotEmpty()) append(SEPARADOR).append(getString(R.string.inventario_resumo_faltam, c.faltando.size))
                if (c.foraDaLista > 0) append(SEPARADOR).append(getString(R.string.inventario_resumo_fora_da_lista, c.foraDaLista))
            }
        }

        // Recusa em linha própria (no máximo 2 linhas, ver layout), no lugar do "\n\n" que esticava o cabeçalho
        txtRecusado.isVisible = recusado != null
        txtRecusado.text = recusado?.let { r ->
            r.ultimoErro?.let { getString(R.string.inventario_recusado_motivo, it) } ?: getString(R.string.inventario_recusado)
        }

        // "Aperte o gatilho…" só até a primeira leitura
        txtStatus.isVisible = RegrasTelaInventario.mostrarInstrucao(totalLidas)

        // Aguardando envio: com o botão "Enviar agora" ao lado (inventário recusado não vai mais ao servidor).
        // Mesma regra da tela principal: com chave, endereço ou certificado errados, repetir o envio não adianta,
        // então no lugar do botão vai "Chame o responsável"
        val acoes = RegrasFaixaSincronizacao.acoes(if (recusado == null) naoEnviadas else 0, estadoSincronizacao)
        linhaEnvio.isVisible = recusado == null && naoEnviadas > 0
        btnEnviarAgora.isVisible = acoes.enviarAgora
        txtAguardando.text = getString(R.string.inventario_resumo_aguardando, naoEnviadas)
        txtChameResponsavel.isVisible = recusado == null && estadoSincronizacao?.dependeDasConfiguracoes == true
    }

    private fun enviarAgora() {
        Log.i(TAG, "Operador pediu o envio agora ($naoEnviadas etiqueta(s) do inventário de ${inventarioAtual?.local} aguardando)")
        Sincronizador.get(this).solicitar()
        Toast.makeText(this, R.string.inventario_enviando, Toast.LENGTH_SHORT).show()
    }

    // ================= ESPERADO =================

    /** Usa na hora a lista guardada no coletor (vale sem rede) e atualiza com a do servidor quando ele responder. */
    private fun carregarEsperado(local: String) {
        txtReferencia.setText(R.string.inventario_buscando_esperado)

        // Leitura e parse do arquivo fora da thread principal; o servidor só é consultado depois, para a lista
        // guardada (mais velha) nunca chegar depois da do servidor e tomar o lugar dela
        emSegundoPlano {
            val guardado = try {
                AppConfig.getEsperadoJson(this, local)?.let { EsperadoLocal.deJson(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Lista do esperado guardada para '$local' ilegível: ignorada", e)
                null
            }
            // ETag só junto de uma lista legível: 304 sem lista para usar deixaria o inventário sem comparação
            val etag = guardado?.let { AppConfig.getEsperadoEtag(this, local) }
            runOnUiThread {
                if (isDestroyed || inventarioAtual?.local != local) return@runOnUiThread
                guardado?.let { aplicarEsperado(it, doServidor = false) }
                buscarEsperadoNoServidor(local, guardado, etag)
            }
        }
    }

    private fun buscarEsperadoNoServidor(local: String, guardado: EsperadoLocal?, etag: String?) {
        tagService.buscarEsperado(local, etag) { resposta ->
            runOnUiThread {
                // O inventário pode ter sido finalizado (ou trocado) enquanto o servidor respondia
                if (isDestroyed || inventarioAtual?.local != local) return@runOnUiThread

                when (resposta) {
                    is RespostaServidor.Sucesso -> {
                        val novo = resposta.valor.esperado
                        val etagNovo = resposta.valor.etag
                        when {
                            // 304: a lista guardada continua valendo, sem baixar de novo (já está na tela)
                            novo == null && guardado != null ->
                                Log.i(TAG, "Lista do esperado de '$local' não mudou no servidor (${guardado.itens.size} etiqueta(s)): usando a guardada sem baixar")

                            // 304 sem lista guardada (não deveria acontecer: o ETag só vai junto de uma lista): pede inteira
                            novo == null -> {
                                Log.w(TAG, "Servidor respondeu 'não mudou' para '$local' sem lista guardada: pedindo a lista inteira")
                                buscarEsperadoNoServidor(local, null, null)
                            }

                            // Igual à guardada (servidor sem ETag, ou ETag novo com o mesmo conteúdo): não regrava a lista
                            // nem recalcula; só guarda o ETag, para a próxima vez vir o 304
                            novo == guardado -> {
                                Log.i(TAG, "Lista do esperado de '$local' do servidor igual à guardada no coletor (${novo.itens.size} etiqueta(s)): mantida")
                                if (etagNovo != null && etagNovo != etag) guardarEtag(local, etagNovo)
                            }

                            else -> {
                                guardarEsperado(local, novo, etagNovo)
                                aplicarEsperado(novo, doServidor = true)
                            }
                        }
                    }

                    RespostaServidor.NaoEncontrado -> {
                        Log.w(TAG, "Servidor sem a lista do esperado (versão antiga): inventário de '$local' sem comparação")
                        if (guardado == null) txtReferencia.setText(R.string.inventario_servidor_desatualizado)
                    }

                    is RespostaServidor.Falha -> {
                        Log.w(TAG, "Lista do esperado de '$local' indisponível (${resposta.mensagem})" +
                            if (guardado != null) ": usando a guardada no coletor" else ": inventário sem comparação")
                        txtReferencia.text = if (guardado != null) getString(R.string.inventario_comparando_guardada, guardado.descricaoReferencia())
                        else getString(R.string.inventario_sem_conexao_esperado)
                    }
                }
            }
        }
    }

    /**
     * Guarda a lista do servidor no coletor (vale sem rede da próxima vez), fora da thread principal. Se a gravação
     * falhar (ex.: memória cheia), só registra no log: a tela segue com a lista em memória.
     */
    private fun guardarEsperado(local: String, esperado: EsperadoLocal, etag: String?) {
        emSegundoPlano {
            try {
                AppConfig.saveEsperadoJson(this, local, esperado.paraJson(), etag)
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível guardar no coletor a lista do esperado de '$local' (memória cheia?): " +
                    "o inventário segue com a lista em memória", e)
            }
        }
    }

    private fun guardarEtag(local: String, etag: String) {
        emSegundoPlano {
            try {
                AppConfig.saveEsperadoEtag(this, local, etag)
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível guardar o ETag da lista do esperado de '$local': a próxima vez ela vem inteira", e)
            }
        }
    }

    /** Devolve false se a tela já fechou (a tarefa não vai rodar). */
    private fun emSegundoPlano(tarefa: () -> Unit): Boolean =
        try {
            arquivos.execute(tarefa)
            true
        } catch (e: RejectedExecutionException) {
            Log.d(TAG, "Tela de inventário já fechada: tarefa em segundo plano ignorada")
            false
        }

    private fun aplicarEsperado(novo: EsperadoLocal, doServidor: Boolean) {
        esperado = novo
        patrimoniosEsperados = novo.itens.associate { it.epc to it.patrimonio }
        temListaEsperada = novo.temReferencia && novo.itens.isNotEmpty()
        val origem = if (doServidor) "do servidor" else "guardada no coletor"

        if (novo.temReferencia) {
            txtReferencia.text = getString(R.string.inventario_comparando, novo.descricaoReferencia(), novo.itens.size)
            Log.i(TAG, "Lista do esperado ($origem) de '${novo.local}': ${novo.itens.size} etiqueta(s), do ${novo.descricaoReferencia()}")
        } else {
            txtReferencia.setText(R.string.inventario_primeiro)
            Log.i(TAG, "Local '${novo.local}' sem inventário finalizado ($origem): primeiro inventário, sem lista do esperado")
        }

        pedirComparacao()
    }

    /**
     * Item que falta: abre o Localizar já com o EPC, que a lista do esperado já traz. Assim localiza sem
     * consultar o servidor (funciona sem rede) e também item sem patrimônio. O patrimônio vai só para exibir.
     */
    private fun localizar(item: ItemEsperado) {
        Log.i(TAG, "Operador foi localizar a etiqueta ${item.epc} (patrimônio ${item.patrimonio ?: "não cadastrado"}), que falta em ${inventarioAtual?.local}")
        abrirLocalizar(item.epc, item.patrimonio)
    }

    /** Lida fora da lista: o operador vai achar a etiqueta para ver o que ela é e por que está ali. */
    private fun localizarForaDaLista(lida: LidaInventario) {
        Log.i(TAG, "Operador foi localizar a etiqueta ${lida.epc}, lida fora da lista em ${inventarioAtual?.local}")
        abrirLocalizar(lida.epc, lida.patrimonio)
    }

    private fun abrirLocalizar(epc: String, patrimonio: String?) {
        if (!CliqueUnico.permitir()) return
        startActivity(
            Intent(this, LocalizarActivity::class.java)
                .putExtra(LocalizarActivity.EXTRA_EPC, epc)
                .putExtra(LocalizarActivity.EXTRA_PATRIMONIO, patrimonio)
        )
    }

    // ================= AÇÕES =================

    private fun iniciar() {
        if (abrindo || !CliqueUnico.permitir()) return
        // Mesma regra do servidor: sem espaços nas pontas nem repetidos, e no máximo 120 caracteres
        val local = LocalInventario.normalizar(edtLocal.text.toString())

        if (local.isEmpty()) {
            Toast.makeText(this, R.string.inventario_informe_local, Toast.LENGTH_SHORT).show()
            return
        }
        if (!LocalInventario.dentroDoLimite(local)) {
            Log.w(TAG, "Inventário não iniciado: local com ${local.length} caracteres (máximo ${LocalInventario.MAXIMO})")
            // No TextInputLayout: a mensagem fica embaixo do campo (no lugar do exemplo), sem o balão do EditText
            campoLocal.error = getString(R.string.inventario_local_grande, LocalInventario.MAXIMO, local.length)
            return
        }
        campoLocal.error = null

        // Local válido: teclado e sugestões saem da frente (da pergunta do local novo e, depois, da lista)
        edtLocal.dismissDropDown()
        fecharTeclado(edtLocal)

        // Local fora da lista costuma ser erro de digitação ("Sala 11" no lugar de "Sala 101"): o inventário
        // ficaria num local que não existe e sem a comparação com o último inventário
        if (RepositorioInventario.localNovo(local, AppConfig.getLocais(this))) {
            AlertDialog.Builder(this)
                .setTitle(R.string.inventario_local_novo_titulo)
                .setMessage(getString(R.string.inventario_local_novo_mensagem, local))
                .setPositiveButton(R.string.inventario_iniciar_mesmo_assim) { _, _ ->
                    Log.i(TAG, "Operador confirmou o local novo '$local' (não está na lista de locais)")
                    abrir(local)
                }
                .setNegativeButton(R.string.inventario_corrigir_local, null)
                .show()
            return
        }

        abrir(local)
    }

    private fun abrir(local: String) {
        if (abrindo) return
        abrindo = true
        repositorio.abrir(local) { ok ->
            runOnUiThread {
                abrindo = false
                when {
                    ok -> {
                        lembrarLocal(local)
                        edtLocal.setText("")
                    }
                    isFinishing || isDestroyed -> Unit
                    // Sessão venceu: vai para o login (antes dizia "Já existe um inventário em andamento")
                    SessaoAtual.get(this) == null -> exigirSessao()
                    else -> Toast.makeText(this, R.string.inventario_em_andamento, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmarFinalizacao() {
        if (dialogoFinalizar?.isShowing == true || !CliqueUnico.permitir()) return
        val inventario = inventarioAtual ?: return
        val totalLidas = epcsLidos.size
        // Calculada na hora (uma vez só): a da tela pode estar até RitmoComparacao.INTERVALO_MS atrasada
        val c = compararAgora()

        val mensagem = buildString {
            append(getString(R.string.inventario_finalizar_mensagem, totalLidas, inventario.local))
            if (c != null && c.faltando.isNotEmpty()) {
                append("\n\n").append(getString(R.string.inventario_finalizar_faltam, c.faltando.size, c.esperados))
            }
            // Inventário recusado de vez não é mais enviado: não prometer que "serão enviadas"
            if (naoEnviadas > 0 && inventario.recusadoEm == null) {
                append("\n\n").append(getString(R.string.inventario_finalizar_nao_enviadas, naoEnviadas))
            }
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.inventario_finalizar_titulo)
            .setMessage(mensagem)
            .setPositiveButton(R.string.inventario_finalizar_confirmar) { _, _ ->
                // O resumo usa as etiquetas GRAVADAS na finalização (e não a lista da tela): o gatilho pode ter lido
                // mais enquanto a pergunta estava aberta, e o total e "o que faltou" precisam bater
                val esperadoAgora = esperado
                val finalizadoEm = System.currentTimeMillis()
                val operador = SessaoAtual.get(this)?.nome
                var final: ComparacaoInventario? = null
                var etiquetasGravadas: List<String> = emptyList()

                repositorio.finalizar(
                    etiquetas = { gravadas ->
                        etiquetasGravadas = gravadas
                        final = esperadoAgora?.takeIf { it.temReferencia }?.let { ComparacaoInventario.comparar(it, gravadas.toSet()) }
                    }
                ) { total ->
                    if (total != null) final?.let { f ->
                        Log.i(TAG, "Operador finalizou o inventário de ${inventario.local} com $total etiqueta(s) " +
                            "(${f.encontradas} de ${f.esperados} esperada(s), faltando ${f.faltando.size}, ${f.foraDaLista} fora da lista)")
                    } ?: Log.i(TAG, "Operador finalizou o inventário de ${inventario.local} com $total etiqueta(s)")
                    runOnUiThread {
                        when {
                            total != null -> mostrarResumoFinal(
                                ResumoFinalizacao(
                                    inventario.local, finalizadoEm, operador, total, final, inventario.id,
                                    etiquetasGravadas, esperadoAgora?.itens.orEmpty()
                                )
                            )
                            isFinishing || isDestroyed -> Unit
                            // Não finalizou porque a sessão terminou: manda para o login ("entre de novo para continuar")
                            SessaoAtual.get(this) == null -> exigirSessao()
                            else -> {
                                Log.i(TAG, "Finalizar do inventário de ${inventario.local} sem inventário aberto: já tinha sido finalizado")
                                Toast.makeText(this, R.string.inventario_ja_finalizado, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton(R.string.inventario_continuar_lendo, null)
            .show()
            .also { dialogoFinalizar = it }
    }

    private fun compararAgora(): ComparacaoInventario? =
        esperado?.takeIf { it.temReferencia }?.let { ComparacaoInventario.comparar(it, epcsLidos) }

    /** Depois de finalizar: o resumo fica na tela até o operador fechar, e pode ser compartilhado. */
    private fun mostrarResumoFinal(resumo: ResumoFinalizacao) {
        if (isFinishing || isDestroyed) return
        val c = resumo.comparacao

        val mensagem = buildString {
            // O local vai na mensagem: no título ("Inventário de X finalizado: N") ele cortava na tela de 320dp
            append(getString(R.string.inventario_local, resumo.local)).append('\n')
            append(getString(R.string.inventario_resumo_lidas_dialogo, resumo.lidas))
            if (c == null) {
                append("\n").append(getString(R.string.inventario_resumo_sem_comparacao))
            } else {
                append("\n").append(getString(R.string.inventario_resumo_encontradas_dialogo, c.encontradas, c.esperados))
                append("\n").append(getString(R.string.inventario_resumo_faltando_dialogo, c.faltando.size))
                append("\n").append(getString(R.string.inventario_resumo_fora_dialogo, c.foraDaLista))
            }
        }

        val faltantes = faltantesSemJustificativa(resumo)
        AlertDialog.Builder(this)
            .setTitle(R.string.inventario_finalizado_titulo)
            .setMessage(mensagem)
            .setPositiveButton(R.string.inventario_compartilhar) { _, _ -> compartilhar(resumo) }
            .apply {
                // Justificar os que faltaram (emprestado, em manutenção...): vai para o servidor junto com o inventário.
                // Com três botões eles empilham (positivo, negativo, neutro): Justificar fica no meio e o OK por último
                if (faltantes.isNotEmpty() && resumo.inventarioId.isNotEmpty()) {
                    setNegativeButton(getString(R.string.inventario_justificar, faltantes.size)) { _, _ -> escolherMotivo(resumo) }
                    setNeutralButton(R.string.comum_ok, null)
                } else {
                    setNegativeButton(R.string.comum_ok, null)
                }
            }
            .show()
    }

    // ================= JUSTIFICATIVAS DOS NÃO ENCONTRADOS =================

    private fun faltantesSemJustificativa(resumo: ResumoFinalizacao): List<ItemEsperado> {
        val justificados = SincronizadorJustificativas.fila(this).motivos(resumo.inventarioId).keys
        return resumo.comparacao?.faltando.orEmpty().filter { it.epc !in justificados }
    }

    /** Passo 1: o motivo. Passo 2 ([escolherFaltantes]): quais faltantes têm esse motivo. Repete até acabar ou desistir. */
    private fun escolherMotivo(resumo: ResumoFinalizacao) {
        if (isFinishing || isDestroyed) return
        val motivos = FilaJustificativas.Motivo.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.inventario_justificar_motivo)
            .setItems(motivos.map { getString(textoMotivo(it)) }.toTypedArray()) { _, i -> escolherFaltantes(resumo, motivos[i]) }
            .setNegativeButton(R.string.comum_cancelar) { _, _ -> mostrarResumoFinal(resumo) }
            .show()
    }

    private fun escolherFaltantes(resumo: ResumoFinalizacao, motivo: FilaJustificativas.Motivo) {
        if (isFinishing || isDestroyed) return
        val faltantes = faltantesSemJustificativa(resumo)
        if (faltantes.isEmpty()) return mostrarResumoFinal(resumo)
        val marcados = BooleanArray(faltantes.size)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.inventario_justificar_quais, getString(textoMotivo(motivo))))
            .setMultiChoiceItems(faltantes.map { ResumoFinalizacao.descreverFaltante(it) }.toTypedArray(), marcados) { _, i, marcado ->
                marcados[i] = marcado
            }
            .setPositiveButton(R.string.inventario_justificar_salvar) { _, _ ->
                val escolhidos = faltantes.filterIndexed { i, _ -> marcados[i] }.map { it.epc }
                when {
                    escolhidos.isEmpty() -> mostrarResumoFinal(resumo)
                    // "Outro" sem explicação não ajuda quem confere na web: pede uma observação curta (opcional)
                    motivo == FilaJustificativas.Motivo.OUTRO -> pedirObservacao(resumo, escolhidos)
                    else -> salvarJustificativas(resumo, escolhidos, motivo, null)
                }
            }
            .setNegativeButton(R.string.comum_cancelar) { _, _ -> mostrarResumoFinal(resumo) }
            .show()
    }

    private fun pedirObservacao(resumo: ResumoFinalizacao, escolhidos: List<String>) {
        if (isFinishing || isDestroyed) return
        val campo = android.widget.EditText(this).apply {
            setHint(R.string.inventario_justificar_observacao_dica)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(android.text.InputFilter.LengthFilter(FilaJustificativas.MAXIMO_OBSERVACAO))
        }
        val margem = (20 * resources.displayMetrics.density).toInt()
        val moldura = android.widget.FrameLayout(this).apply {
            setPadding(margem, margem / 2, margem, 0)
            addView(campo)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.inventario_justificar_observacao)
            .setView(moldura)
            .setPositiveButton(R.string.inventario_justificar_salvar) { _, _ ->
                salvarJustificativas(resumo, escolhidos, FilaJustificativas.Motivo.OUTRO, campo.text.toString())
            }
            .setNegativeButton(R.string.comum_cancelar) { _, _ -> mostrarResumoFinal(resumo) }
            .show()
    }

    private fun salvarJustificativas(resumo: ResumoFinalizacao, epcs: List<String>, motivo: FilaJustificativas.Motivo, observacao: String?) {
        val sessao = SessaoAtual.token(this)?.let { CofreSessoes.get(this).referencia(it) }
        SincronizadorJustificativas.fila(this).adicionar(resumo.inventarioId, epcs, motivo, sessao, System.currentTimeMillis(), observacao)
        Log.i(TAG, "Operador justificou ${epcs.size} faltante(s) do inventário de ${resumo.local} como $motivo")
        Sincronizador.get(this).solicitarEmBreve()
        // Ainda há faltantes sem motivo: volta ao resumo (com o botão de justificar mostrando quantos faltam)
        mostrarResumoFinal(resumo)
    }

    private fun textoMotivo(motivo: FilaJustificativas.Motivo): Int = when (motivo) {
        FilaJustificativas.Motivo.EMPRESTADO -> R.string.justificativa_emprestado
        FilaJustificativas.Motivo.MANUTENCAO -> R.string.justificativa_manutencao
        FilaJustificativas.Motivo.BAIXADO -> R.string.justificativa_baixado
        FilaJustificativas.Motivo.NAO_ENCONTRADO -> R.string.justificativa_nao_encontrado
        FilaJustificativas.Motivo.OUTRO -> R.string.justificativa_outro
    }

    /**
     * Texto do resumo e, anexada, a planilha (CSV) com uma linha por etiqueta e a justificativa dos que faltaram.
     * Sem como gravar a planilha (memória cheia), vai só o texto, como antes.
     */
    private fun compartilhar(resumo: ResumoFinalizacao) {
        val envio = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.inventario_compartilhar_assunto, resumo.local))
            .putExtra(Intent.EXTRA_TEXT, resumo.textoCompartilhar())

        planilha(resumo)?.let { uri ->
            envio.setType("text/csv")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(Intent.createChooser(envio, getString(R.string.inventario_compartilhar_escolha)))
            Log.i(TAG, "Operador compartilhou o resumo do inventário de ${resumo.local}")
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Nenhum app para compartilhar o resumo do inventário de ${resumo.local}", e)
            Toast.makeText(this, R.string.inventario_compartilhar_indisponivel, Toast.LENGTH_LONG).show()
        }
    }

    /** Grava a planilha no cache (pasta liberada no FileProvider) e devolve o endereço para anexar; null se não deu. */
    private fun planilha(resumo: ResumoFinalizacao): android.net.Uri? =
        try {
            val motivos = SincronizadorJustificativas.fila(this).motivos(resumo.inventarioId)
                .mapValues { getString(textoMotivo(it.value)) }
            val pasta = java.io.File(cacheDir, "relatorios").apply { mkdirs() }
            pasta.listFiles()?.forEach { it.delete() } // só a última planilha fica guardada
            val nome = "inventario-" + resumo.local.replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(40).ifEmpty { "local" } + ".csv"
            val arquivo = java.io.File(pasta, nome)
            // BOM do UTF-8: o Excel reconhece os acentos
            arquivo.writeText(BOM_UTF8 + resumo.csv(motivos), Charsets.UTF_8)
            androidx.core.content.FileProvider.getUriForFile(this, "$packageName.atualizacao", arquivo)
        } catch (e: Exception) {
            Log.w(TAG, "Planilha do inventário de ${resumo.local} não gerada: compartilhando só o texto", e)
            null
        }

    /**
     * Cancelar: só enquanto nada foi enviado ao servidor (ex.: iniciado no local errado, sem rede). Depois disso
     * o servidor já tem o inventário e o caminho é finalizar. O repositório confere de novo na hora de apagar.
     */
    private fun confirmarCancelamento() {
        val inventario = inventarioAtual ?: return
        if (!RepositorioInventario.podeCancelar(inventario, etiquetasEnviadas = 0)) {
            mostrarNaoCancelavel()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.inventario_cancelar_titulo)
            .setMessage(getString(R.string.inventario_cancelar_mensagem, inventario.local, epcsLidos.size))
            .setPositiveButton(R.string.inventario_cancelar_confirmar) { _, _ ->
                repositorio.cancelar { resultado ->
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        when (resultado) {
                            RepositorioInventario.ResultadoCancelamento.CANCELADO ->
                                Toast.makeText(this, getString(R.string.inventario_cancelado, inventario.local), Toast.LENGTH_LONG).show()
                            RepositorioInventario.ResultadoCancelamento.JA_ENVIADO -> mostrarNaoCancelavel()
                            RepositorioInventario.ResultadoCancelamento.SEM_INVENTARIO -> Unit
                        }
                    }
                }
            }
            .setNegativeButton(R.string.inventario_cancelar_voltar, null)
            .show()
    }

    private fun mostrarNaoCancelavel() {
        AlertDialog.Builder(this)
            .setTitle(R.string.inventario_nao_cancelavel_titulo)
            .setMessage(R.string.inventario_nao_cancelavel_mensagem)
            .setPositiveButton(R.string.comum_ok, null)
            .show()
    }

    // ================= LOCAIS =================

    /** Sugestões: as guardadas no coletor na hora, e as do servidor quando ele responder. */
    private fun carregarLocais() {
        mostrarSugestoes(AppConfig.getLocais(this))

        tagService.listarLocais { resposta ->
            runOnUiThread {
                when (resposta) {
                    is RespostaServidor.Sucesso -> {
                        val todos = (AppConfig.getLocais(this) + resposta.valor).distinctBy { it.lowercase() }
                        AppConfig.saveLocais(this, todos)
                        mostrarSugestoes(todos)
                        Log.d(TAG, "${resposta.valor.size} local(is) recebido(s) do servidor")
                    }
                    else -> Log.w(TAG, "Lista de locais do servidor indisponível: usando os guardados no coletor")
                }
            }
        }
    }

    private fun lembrarLocal(local: String) {
        val todos = (AppConfig.getLocais(this) + local).distinctBy { it.lowercase() }
        AppConfig.saveLocais(this, todos)
        mostrarSugestoes(todos)
    }

    private fun mostrarSugestoes(locais: List<String>) {
        edtLocal.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, locais.sortedBy { it.lowercase() }))
    }

    /** Campo vazio, na frente e com locais conhecidos: abre a lista inteira (com texto, valem as sugestões filtradas). */
    private fun mostrarTodosOsLocais() {
        if (isFinishing || isDestroyed || !edtLocal.isShown || !edtLocal.hasWindowFocus()) return
        if (edtLocal.text.isNullOrBlank() && (edtLocal.adapter?.count ?: 0) > 0 && !edtLocal.isPopupShowing) {
            edtLocal.showDropDown()
        }
    }

    companion object {
        private const val TAG = "INVENTARIO"
        /** Marca de UTF-8 no início da planilha: o Excel reconhece os acentos. */
        private val BOM_UTF8 = Char(0xFEFF).toString()
        private const val ABA_FALTANDO = 0
        private const val ABA_LIDAS = 1

        // Separa as partes do resumo do inventário (não é texto a traduzir)
        private const val SEPARADOR = "  •  "
    }
}
