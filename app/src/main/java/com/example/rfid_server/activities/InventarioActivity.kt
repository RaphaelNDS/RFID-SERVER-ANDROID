package com.example.rfid_server.activities

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
import com.example.rfid_server.adapter.TagNaoAdapter
import com.example.rfid_server.dados.InventarioEntity
import com.example.rfid_server.dados.InventarioItemEntity
import com.example.rfid_server.dados.RepositorioInventario
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService

/**
 * Inventário por local: o operador escolhe o local, inicia, lê as etiquetas com o gatilho e finaliza.
 * Tudo fica guardado no coletor e é enviado ao servidor quando houver rede (Sincronizador).
 * Se o app fechar no meio, o inventário continua aberto e é retomado aqui.
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
    private lateinit var txtStatus: TextView
    private lateinit var adapter: TagNaoAdapter

    private var inventarioAtual: InventarioEntity? = null
    private var itensObservados: LiveData<List<InventarioItemEntity>>? = null
    private var naoEnviadosObservados: LiveData<Int>? = null
    private var totalLidas = 0
    private var naoEnviadas = 0

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
        txtStatus = findViewById(R.id.txtStatus)

        adapter = TagNaoAdapter()
        findViewById<RecyclerView>(R.id.recyclerItens).apply {
            layoutManager = LinearLayoutManager(this@InventarioActivity)
            adapter = this@InventarioActivity.adapter
        }

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
            ativarLeitor()
        }
    }

    private fun observarItens(inventarioId: String) {
        pararDeObservarItens()

        itensObservados = repositorio.itens(inventarioId).also { liveData ->
            liveData.observe(this) { itens ->
                totalLidas = itens.size
                adapter.atualizar(itens.map { it.epc })
                atualizarResumo()
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
        totalLidas = 0
        naoEnviadas = 0
        adapter.atualizar(emptyList())
    }

    private fun atualizarResumo() {
        txtResumo.text = buildString {
            append("$totalLidas etiqueta(s) lida(s)")
            if (naoEnviadas > 0) append("  •  $naoEnviadas aguardando envio ao servidor")
        }
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

        val mensagem = buildString {
            append("$totalLidas etiqueta(s) lida(s) em ${inventario.local}. Depois de finalizar, não é possível ler mais neste inventário.")
            if (naoEnviadas > 0) append("\n\n$naoEnviadas ainda não chegaram ao servidor: continuam guardadas no coletor e serão enviadas assim que possível.")
        }

        AlertDialog.Builder(this)
            .setTitle("Finalizar inventário?")
            .setMessage(mensagem)
            .setPositiveButton("Finalizar") { _, _ ->
                Log.i(TAG, "Operador finalizou o inventário de ${inventario.local} com $totalLidas etiqueta(s)")
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
    }
}
