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

/**
 * Inventário por local: o operador escolhe o local, inicia, lê as etiquetas com o gatilho e finaliza.
 * Tudo fica guardado no coletor e é enviado ao servidor quando houver rede (Sincronizador).
 * Se o app fechar no meio, o inventário continua aberto e é retomado aqui.
 *
 * Se o local já teve inventário finalizado, a tela mostra ao vivo o que falta, comparando com o que foi
 * encontrado da última vez (GET /api/android/inventarios/esperado). Tocar num item que falta abre o
 * Localizar já com o patrimônio dele. A lista fica guardada no coletor e vale sem rede.
 */
class InventarioActivity : AppCompatActivity() {

    private lateinit var repositorio: RepositorioInventario
    private lateinit var rfidManager: RfidManager
    private lateinit var tagService: TagService

    private lateinit var painelInicio: LinearLayout
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
    private var comparacao: ComparacaoInventario? = null
    private var avisouCompleto = false

    private lateinit var aviso: AvisoLeitura

    // Etiqueta nova no inventário: bip/vibração
    private val ouvinte = RfidManager.OuvinteLeitura { epc, _ -> repositorio.registrar(epc) { aviso.tagNova() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_inventario)

        repositorio = RepositorioInventario.get(this)
        rfidManager = RfidManager.get(this)
        tagService = TagService(this)
        aviso = AvisoLeitura(this)

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

        tabs.addTab(tabs.newTab().setText("Faltando"))
        tabs.addTab(tabs.newTab().setText("Lidas"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                recycler.adapter = if (tab.position == ABA_FALTANDO) adapterFaltando else adapterLidas
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        findViewById<Button>(R.id.btnIniciar).setOnClickListener { iniciar() }
        findViewById<Button>(R.id.btnFinalizar).setOnClickListener { confirmarFinalizacao() }

        mostrarEstadoLeitor(rfidManager)
        carregarLocais()

        repositorio.inventarioAberto.observe(this) { mostrar(it) }
    }

    // O leitor só manda leituras para cá enquanto há inventário aberto e a tela está visível
    override fun onResume() {
        super.onResume()
        exigirSessao() ?: return
        if (inventarioAtual != null) ativarLeitor()
    }

    override fun onPause() {
        super.onPause()
        rfidManager.removerOuvinte(this)
    }

    // Conecta o leitor, se preciso, e reconecta sozinho se cair; a situação aparece na faixa do topo
    private fun ativarLeitor() {
        rfidManager.definirOuvinte(this, ouvinte)
    }

    override fun onDestroy() {
        super.onDestroy()
        aviso.liberar()
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
        txtLocal.text = "Local: ${inventario.local}"

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
        val patrimonios = atual?.itens?.associate { it.epc to it.patrimonio }.orEmpty()
        adapterLidas.atualizar(epcsLidos.map { epc -> patrimonios[epc]?.let { "Patrimônio $it  ($epc)" } ?: epc })
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
            tabs.getTabAt(ABA_FALTANDO)?.text = "Faltando (${nova.faltando.size})"
            tabs.getTabAt(ABA_LIDAS)?.text = "Lidas (${epcsLidos.size})"
            avisarSeCompleto(nova)
        }

        atualizarResumo()
    }

    private fun avisarSeCompleto(nova: ComparacaoInventario) {
        if (nova.esperados == 0 || nova.faltando.isNotEmpty() || avisouCompleto) return
        avisouCompleto = true

        val local = inventarioAtual?.local
        Log.i(TAG, "Todas as ${nova.esperados} etiqueta(s) esperada(s) em $local foram encontradas (${nova.foraDaLista} fora da lista)")
        Toast.makeText(this, "Todas as ${nova.esperados} etiquetas esperadas foram encontradas", Toast.LENGTH_LONG).show()
    }

    private fun atualizarResumo() {
        val c = comparacao
        txtResumo.text = buildString {
            append("${epcsLidos.size} etiqueta(s) lida(s)")
            if (c != null) {
                append("  •  ${c.encontradas} de ${c.esperados} esperada(s)")
                if (c.faltando.isNotEmpty()) append("  •  faltam ${c.faltando.size}")
                if (c.foraDaLista > 0) append("  •  ${c.foraDaLista} fora da lista")
            }
            if (naoEnviadas > 0) append("  •  $naoEnviadas aguardando envio ao servidor")
        }
    }

    // ================= ESPERADO =================

    /** Usa na hora a lista guardada no coletor (vale sem rede) e atualiza com a do servidor quando ele responder. */
    private fun carregarEsperado(local: String) {
        val guardado = AppConfig.getEsperadoJson(this, local)?.let { json ->
            try {
                EsperadoLocal.deJson(json)
            } catch (e: Exception) {
                Log.w(TAG, "Lista do esperado guardada para '$local' ilegível: ignorada", e)
                null
            }
        }
        guardado?.let { aplicarEsperado(it, doServidor = false) }
            ?: run { txtReferencia.text = "Buscando no servidor o que é esperado neste local..." }

        tagService.buscarEsperado(local) { resposta ->
            runOnUiThread {
                // O inventário pode ter sido finalizado (ou trocado) enquanto o servidor respondia
                if (inventarioAtual?.local != local) return@runOnUiThread

                when (resposta) {
                    is RespostaServidor.Sucesso -> {
                        AppConfig.saveEsperadoJson(this, local, resposta.valor.paraJson())
                        aplicarEsperado(resposta.valor, doServidor = true)
                    }

                    RespostaServidor.NaoEncontrado -> {
                        Log.w(TAG, "Servidor sem a lista do esperado (versão antiga): inventário de '$local' sem comparação")
                        if (guardado == null) txtReferencia.text = "Servidor desatualizado: sem a lista do que é esperado"
                    }

                    is RespostaServidor.Falha -> {
                        Log.w(TAG, "Lista do esperado de '$local' indisponível (${resposta.mensagem})" +
                            if (guardado != null) ": usando a guardada no coletor" else ": inventário sem comparação")
                        txtReferencia.text = if (guardado != null) "Comparando com o ${guardado.descricaoReferencia()} (lista guardada no coletor)"
                        else "Sem conexão: não deu para buscar o que é esperado neste local"
                    }
                }
            }
        }
    }

    private fun aplicarEsperado(novo: EsperadoLocal, doServidor: Boolean) {
        esperado = novo
        val origem = if (doServidor) "do servidor" else "guardada no coletor"

        if (novo.temReferencia) {
            txtReferencia.text = "Comparando com o ${novo.descricaoReferencia()}: ${novo.itens.size} etiqueta(s) esperada(s)"
            Log.i(TAG, "Lista do esperado ($origem) de '${novo.local}': ${novo.itens.size} etiqueta(s), do ${novo.descricaoReferencia()}")
        } else {
            txtReferencia.text = "Primeiro inventário deste local: não há lista do que é esperado"
            Log.i(TAG, "Local '${novo.local}' sem inventário finalizado ($origem): primeiro inventário, sem lista do esperado")
        }

        atualizarComparacao()
    }

    /** Item que falta: abre o Localizar já buscando o patrimônio. */
    private fun localizar(item: ItemEsperado) {
        val patrimonio = item.patrimonio ?: run {
            Log.w(TAG, "Item esperado ${item.epc} sem patrimônio no cadastro: não dá para localizar pelo patrimônio")
            Toast.makeText(this, "Esta etiqueta não tem patrimônio cadastrado: não dá para localizar", Toast.LENGTH_LONG).show()
            return
        }

        Log.i(TAG, "Operador foi localizar o patrimônio $patrimonio, que falta em ${inventarioAtual?.local}")
        startActivity(Intent(this, LocalizarActivity::class.java).putExtra(LocalizarActivity.EXTRA_PATRIMONIO, patrimonio))
    }

    // ================= AÇÕES =================

    private fun iniciar() {
        val local = edtLocal.text.toString().trim()

        if (local.isEmpty()) {
            Toast.makeText(this, "Informe o local do inventário", Toast.LENGTH_SHORT).show()
            return
        }

        repositorio.abrir(local) { ok ->
            runOnUiThread {
                if (ok) {
                    lembrarLocal(local)
                    edtLocal.setText("")
                } else {
                    Toast.makeText(this, "Já existe um inventário em andamento", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun confirmarFinalizacao() {
        val inventario = inventarioAtual ?: return
        val totalLidas = epcsLidos.size
        val c = comparacao

        val mensagem = buildString {
            append("$totalLidas etiqueta(s) lida(s) em ${inventario.local}. Depois de finalizar, não é possível ler mais neste inventário.")
            if (c != null && c.faltando.isNotEmpty()) {
                append("\n\nAtenção: faltam ${c.faltando.size} das ${c.esperados} etiqueta(s) encontradas no último inventário deste local.")
            }
            if (naoEnviadas > 0) append("\n\n$naoEnviadas ainda não chegaram ao servidor: continuam guardadas no coletor e serão enviadas assim que possível.")
        }

        AlertDialog.Builder(this)
            .setTitle("Finalizar inventário?")
            .setMessage(mensagem)
            .setPositiveButton("Finalizar") { _, _ ->
                Log.i(TAG, "Operador finalizou o inventário de ${inventario.local} com $totalLidas etiqueta(s)" +
                    if (c != null) " (${c.encontradas} de ${c.esperados} esperada(s), faltando ${c.faltando.size}, ${c.foraDaLista} fora da lista)" else "")
                repositorio.finalizar { total ->
                    runOnUiThread {
                        if (total != null) {
                            Toast.makeText(this, "Inventário de ${inventario.local} finalizado: $total etiqueta(s)", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
            .setNegativeButton("Continuar lendo", null)
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
    }
}
