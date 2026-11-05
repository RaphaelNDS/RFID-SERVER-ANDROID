package com.example.rfid_server.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LiveData
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.AppConfig
import com.example.rfid_server.AvisoLeitura
import com.example.rfid_server.R
import com.example.rfid_server.RfidManager
import com.example.rfid_server.adapter.EsperadoAdapter
import com.example.rfid_server.adapter.TagNaoAdapter
import com.example.rfid_server.dados.ComparacaoInventario
import com.example.rfid_server.dados.EsperadoLocal
import com.example.rfid_server.dados.InventarioEntity
import com.example.rfid_server.dados.InventarioItemEntity
import com.example.rfid_server.dados.ItemEsperado
import com.example.rfid_server.dados.RepositorioInventario
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService
import com.google.android.material.tabs.TabLayout
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
    private lateinit var txtLocal: TextView
    private lateinit var txtResumo: TextView
    private lateinit var txtReferencia: TextView
    private lateinit var txtStatus: TextView
    private lateinit var tabs: TabLayout
    private lateinit var recycler: RecyclerView
    private lateinit var adapterLidas: TagNaoAdapter
    private lateinit var adapterFaltando: EsperadoAdapter

    private var inventarioAtual: InventarioEntity? = null
    private var itensObservados: LiveData<List<InventarioItemEntity>>? = null
    private var naoEnviadosObservados: LiveData<Int>? = null
    private var epcsLidos: List<String> = emptyList()
    private var naoEnviadas = 0

    private var esperado: EsperadoLocal? = null

    // EPC → patrimônio do esperado: montado uma vez quando a lista chega, e não a cada etiqueta lida
    private var patrimoniosEsperados: Map<String, String?> = emptyMap()
    private var comparacao: ComparacaoInventario? = null
    private var avisouCompleto = false

    private lateinit var aviso: AvisoLeitura

    // Lê e grava a lista do esperado guardada no coletor fora da thread principal: com milhares de etiquetas,
    // ou com a memória cheia (a gravação falha), a tela travava ou fechava
    private val arquivos = Executors.newSingleThreadExecutor()

    // Etiqueta nova no inventário: bip/vibração
    private val ouvinte = RfidManager.OuvinteLeitura { epc, _ -> repositorio.registrar(epc) { aviso.tagNova() } }

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
        txtLocal = findViewById(R.id.txtLocal)
        txtResumo = findViewById(R.id.txtResumo)
        txtReferencia = findViewById(R.id.txtReferencia)
        txtStatus = findViewById(R.id.txtStatus)
        tabs = findViewById(R.id.tabsInventario)

        adapterLidas = TagNaoAdapter()
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
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        findViewById<Button>(R.id.btnIniciar).setOnClickListener { iniciar() }
        findViewById<Button>(R.id.btnFinalizar).setOnClickListener { confirmarFinalizacao() }
        findViewById<Button>(R.id.btnCancelarInventario).setOnClickListener { confirmarCancelamento() }

        mostrarEstadoLeitor(rfidManager)
        carregarLocais()

        repositorio.inventarioAberto.observe(this) { mostrar(it) }
        FaixaRecusa.ligar(this, repositorio)
    }

    // Um inventário passou a aparecer na tela (ver mostrar). No onResume quem liga é a TelaDeLeitura.
    // Conecta o leitor, se preciso, e reconecta sozinho se cair; a situação aparece na faixa do topo
    private fun ativarLeitor() {
        rfidManager.definirOuvinte(this, ouvinte)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::aviso.isInitialized) aviso.liberar()
        arquivos.shutdown()
    }

    // ================= TELA =================

    private fun mostrar(inventario: InventarioEntity?) {
        val mudou = inventario?.id != inventarioAtual?.id
        inventarioAtual = inventario

        if (inventario == null) {
            painelInicio.visibility = View.VISIBLE
            painelAndamento.visibility = View.GONE
            rfidManager.removerOuvinte(this)
            pararDeObservarItens()
            return
        }

        painelInicio.visibility = View.GONE
        painelAndamento.visibility = View.VISIBLE
        txtLocal.text = getString(R.string.inventario_local, inventario.local)
        atualizarResumo()

        if (mudou) {
            observarItens(inventario.id)
            carregarEsperado(inventario.local)
            ativarLeitor()
        }
    }

    private fun observarItens(inventarioId: String) {
        pararDeObservarItens()

        itensObservados = repositorio.itens(inventarioId).also { liveData ->
            liveData.observe(this) { itens ->
                epcsLidos = itens.map { it.epc }
                atualizarComparacao()
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
        itensObservados?.removeObservers(this)
        naoEnviadosObservados?.removeObservers(this)
        itensObservados = null
        naoEnviadosObservados = null
        epcsLidos = emptyList()
        naoEnviadas = 0
        esperado = null
        patrimoniosEsperados = emptyMap()
        comparacao = null
        avisouCompleto = false
        adapterLidas.atualizar(emptyList())
        adapterFaltando.atualizar(emptyList())
        tabs.visibility = View.GONE
        txtReferencia.text = ""
    }

    /** Recalcula o que falta a cada etiqueta lida (ou quando chega a lista do esperado). */
    private fun atualizarComparacao() {
        val atual = esperado?.takeIf { it.temReferencia }
        val nova = atual?.let { ComparacaoInventario.comparar(it, epcsLidos) }
        comparacao = nova

        // Lidas: com o patrimônio, quando a etiqueta está na lista do esperado
        val patrimonios = if (atual != null) patrimoniosEsperados else emptyMap()
        adapterLidas.atualizar(epcsLidos.map { epc -> patrimonios[epc]?.let { getString(R.string.inventario_lida_com_patrimonio, it, epc) } ?: epc })
        adapterFaltando.atualizar(nova?.faltando.orEmpty())

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
            tabs.getTabAt(ABA_LIDAS)?.text = getString(R.string.inventario_aba_lidas_qtd, epcsLidos.size)
            avisarSeCompleto(nova)
        }

        atualizarResumo()
    }

    private fun avisarSeCompleto(nova: ComparacaoInventario) {
        if (nova.esperados == 0 || nova.faltando.isNotEmpty() || avisouCompleto) return
        avisouCompleto = true

        val local = inventarioAtual?.local
        Log.i(TAG, "Todas as ${nova.esperados} etiqueta(s) esperada(s) em $local foram encontradas (${nova.foraDaLista} fora da lista)")
        Toast.makeText(this, getString(R.string.inventario_todas_encontradas, nova.esperados), Toast.LENGTH_LONG).show()
    }

    private fun atualizarResumo() {
        val c = comparacao
        txtResumo.text = buildString {
            append(getString(R.string.inventario_resumo_lidas, epcsLidos.size))
            if (c != null) {
                append(SEPARADOR).append(getString(R.string.inventario_resumo_esperadas, c.encontradas, c.esperados))
                if (c.faltando.isNotEmpty()) append(SEPARADOR).append(getString(R.string.inventario_resumo_faltam, c.faltando.size))
                if (c.foraDaLista > 0) append(SEPARADOR).append(getString(R.string.inventario_resumo_fora_da_lista, c.foraDaLista))
            }
            val recusado = inventarioAtual?.takeIf { it.recusadoEm != null }
            if (recusado != null) {
                append("\n\n").append(
                    recusado.ultimoErro?.let { getString(R.string.inventario_recusado_motivo, it) }
                        ?: getString(R.string.inventario_recusado)
                )
            } else if (naoEnviadas > 0) {
                append(SEPARADOR).append(getString(R.string.inventario_resumo_aguardando, naoEnviadas))
            }
        }
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
            runOnUiThread {
                if (isDestroyed || inventarioAtual?.local != local) return@runOnUiThread
                guardado?.let { aplicarEsperado(it, doServidor = false) }
                buscarEsperadoNoServidor(local, guardado)
            }
        }
    }

    private fun buscarEsperadoNoServidor(local: String, guardado: EsperadoLocal?) {
        tagService.buscarEsperado(local) { resposta ->
            runOnUiThread {
                // O inventário pode ter sido finalizado (ou trocado) enquanto o servidor respondia
                if (isDestroyed || inventarioAtual?.local != local) return@runOnUiThread

                when (resposta) {
                    is RespostaServidor.Sucesso -> {
                        guardarEsperado(local, resposta.valor)
                        aplicarEsperado(resposta.valor, doServidor = true)
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
    private fun guardarEsperado(local: String, esperado: EsperadoLocal) {
        emSegundoPlano {
            try {
                AppConfig.saveEsperadoJson(this, local, esperado.paraJson())
            } catch (e: Exception) {
                Log.w(TAG, "Não foi possível guardar no coletor a lista do esperado de '$local' (memória cheia?): " +
                    "o inventário segue com a lista em memória", e)
            }
        }
    }

    private fun emSegundoPlano(tarefa: () -> Unit) {
        try {
            arquivos.execute(tarefa)
        } catch (e: RejectedExecutionException) {
            Log.d(TAG, "Tela de inventário já fechada: tarefa de arquivo ignorada")
        }
    }

    private fun aplicarEsperado(novo: EsperadoLocal, doServidor: Boolean) {
        esperado = novo
        patrimoniosEsperados = novo.itens.associate { it.epc to it.patrimonio }
        val origem = if (doServidor) "do servidor" else "guardada no coletor"

        if (novo.temReferencia) {
            txtReferencia.text = getString(R.string.inventario_comparando, novo.descricaoReferencia(), novo.itens.size)
            Log.i(TAG, "Lista do esperado ($origem) de '${novo.local}': ${novo.itens.size} etiqueta(s), do ${novo.descricaoReferencia()}")
        } else {
            txtReferencia.setText(R.string.inventario_primeiro)
            Log.i(TAG, "Local '${novo.local}' sem inventário finalizado ($origem): primeiro inventário, sem lista do esperado")
        }

        atualizarComparacao()
    }

    /**
     * Item que falta: abre o Localizar já com o EPC, que a lista do esperado já traz. Assim localiza sem
     * consultar o servidor (funciona sem rede) e também item sem patrimônio. O patrimônio vai só para exibir.
     */
    private fun localizar(item: ItemEsperado) {
        Log.i(TAG, "Operador foi localizar a etiqueta ${item.epc} (patrimônio ${item.patrimonio ?: "não cadastrado"}), que falta em ${inventarioAtual?.local}")
        startActivity(
            Intent(this, LocalizarActivity::class.java)
                .putExtra(LocalizarActivity.EXTRA_EPC, item.epc)
                .putExtra(LocalizarActivity.EXTRA_PATRIMONIO, item.patrimonio)
        )
    }

    // ================= AÇÕES =================

    private fun iniciar() {
        val local = edtLocal.text.toString().trim()

        if (local.isEmpty()) {
            Toast.makeText(this, R.string.inventario_informe_local, Toast.LENGTH_SHORT).show()
            return
        }

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
        repositorio.abrir(local) { ok ->
            runOnUiThread {
                if (ok) {
                    lembrarLocal(local)
                    edtLocal.setText("")
                } else {
                    Toast.makeText(this, R.string.inventario_em_andamento, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmarFinalizacao() {
        val inventario = inventarioAtual ?: return
        val totalLidas = epcsLidos.size
        val c = comparacao

        val mensagem = buildString {
            append(getString(R.string.inventario_finalizar_mensagem, totalLidas, inventario.local))
            if (c != null && c.faltando.isNotEmpty()) {
                append("\n\n").append(getString(R.string.inventario_finalizar_faltam, c.faltando.size, c.esperados))
            }
            if (naoEnviadas > 0) append("\n\n").append(getString(R.string.inventario_finalizar_nao_enviadas, naoEnviadas))
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.inventario_finalizar_titulo)
            .setMessage(mensagem)
            .setPositiveButton(R.string.inventario_finalizar_confirmar) { _, _ ->
                Log.i(TAG, "Operador finalizou o inventário de ${inventario.local} com $totalLidas etiqueta(s)" +
                    if (c != null) " (${c.encontradas} de ${c.esperados} esperada(s), faltando ${c.faltando.size}, ${c.foraDaLista} fora da lista)" else "")
                repositorio.finalizar { total ->
                    runOnUiThread {
                        if (total != null) {
                            Toast.makeText(this, getString(R.string.inventario_finalizado, inventario.local, total), Toast.LENGTH_LONG).show()
                        } else {
                            // Não finalizou porque a sessão terminou: manda para o login ("entre de novo para continuar")
                            exigirSessao()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.inventario_continuar_lendo, null)
            .show()
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

    companion object {
        private const val TAG = "INVENTARIO"
        private const val ABA_FALTANDO = 0
        private const val ABA_LIDAS = 1

        // Separa as partes do resumo do inventário (não é texto a traduzir)
        private const val SEPARADOR = "  •  "
    }
}
