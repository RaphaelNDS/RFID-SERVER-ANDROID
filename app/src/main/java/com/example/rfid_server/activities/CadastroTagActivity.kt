package com.example.rfid_server.activities

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.rfid_server.AppConfig
import com.example.rfid_server.R
import com.example.rfid_server.SessaoAtual
import com.example.rfid_server.dados.CadastroEntity
import com.example.rfid_server.dados.CatalogoColetor
import com.example.rfid_server.dados.MarcaCatalogo
import com.example.rfid_server.dados.RepositorioCadastros
import com.example.rfid_server.dados.SituacaoCadastro
import com.example.rfid_server.dados.TipoCatalogo
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService
import org.json.JSONException

/**
 * Cadastro de uma etiqueta não cadastrada, feito no coletor. Funciona sem rede: usa o catálogo
 * guardado no aparelho, grava o cadastro e o Sincronizador envia ao servidor depois.
 * Mesmas regras da web: só o código interno é obrigatório; tipo → marca → modelo em cascata.
 */
class CadastroTagActivity : AppCompatActivity() {

    /** Opção de um Spinner; id null = "não informado". */
    private data class Opcao(val id: Long?, val nome: String) {
        override fun toString() = nome
    }

    private lateinit var epc: String
    private lateinit var repositorio: RepositorioCadastros

    private lateinit var txtAviso: TextView
    private lateinit var edtCodigoInterno: EditText
    private lateinit var edtPatrimonio: EditText
    private lateinit var edtNumeroSerie: EditText
    private lateinit var spnTipo: Spinner
    private lateinit var spnMarca: Spinner
    private lateinit var spnModelo: Spinner
    private lateinit var btnSalvar: Button

    private var catalogo: CatalogoColetor? = null
    private var tipoAtual: TipoCatalogo? = null
    private var marcaAtual: MarcaCatalogo? = null

    // Seleções a aplicar quando a cascata chegar no Spinner (prefill ou catálogo recarregado)
    private var marcaPendente: Long? = null
    private var modeloPendente: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cadastro_tag)

        epc = intent.getStringExtra(EXTRA_EPC) ?: run {
            Log.e(TAG, "Tela de cadastro aberta sem EPC")
            finish()
            return
        }
        repositorio = RepositorioCadastros.get(this)

        findViewById<TextView>(R.id.txtEpc).text = "Etiqueta $epc"
        txtAviso = findViewById(R.id.txtAviso)
        edtCodigoInterno = findViewById(R.id.edtCodigoInterno)
        edtPatrimonio = findViewById(R.id.edtPatrimonio)
        edtNumeroSerie = findViewById(R.id.edtNumeroSerie)
        spnTipo = findViewById(R.id.spnTipo)
        spnMarca = findViewById(R.id.spnMarca)
        spnModelo = findViewById(R.id.spnModelo)
        btnSalvar = findViewById(R.id.btnSalvar)

        configurarCascata()
        btnSalvar.setOnClickListener { salvar() }

        aplicarCatalogo(lerCatalogoGuardado())
        preencherComCadastroGuardado()
        atualizarCatalogoDoServidor()
    }

    // ================= CATÁLOGO =================

    private fun lerCatalogoGuardado(): CatalogoColetor? {
        val json = AppConfig.getCatalogoJson(this) ?: return null
        return try {
            CatalogoColetor.deJson(json)
        } catch (e: JSONException) {
            Log.e(TAG, "Catálogo guardado no coletor está corrompido: será baixado de novo", e)
            null
        }
    }

    private fun atualizarCatalogoDoServidor() {
        TagService(this).buscarCatalogo { resposta ->
            runOnUiThread {
                if (resposta is RespostaServidor.Sucesso) {
                    AppConfig.saveCatalogoJson(this, resposta.valor)
                    val novo = CatalogoColetor.deJson(resposta.valor)
                    Log.i(TAG, "Catálogo atualizado do servidor: ${novo.tipos.size} tipo(s); coletor " +
                        if (novo.podeCadastrar) "liberado para cadastrar" else "SEM permissão para cadastrar")
                    aplicarCatalogo(novo, manterSelecao = true)
                } else {
                    Log.w(TAG, "Catálogo do servidor indisponível: usando o guardado no coletor")
                }
            }
        }
    }

    private fun aplicarCatalogo(novo: CatalogoColetor?, manterSelecao: Boolean = false) {
        if (manterSelecao) {
            marcaPendente = marcaPendente ?: marcaAtual?.id
            modeloPendente = modeloPendente ?: (spnModelo.selectedItem as? Opcao)?.id
        }
        val tipoSelecionado = if (manterSelecao) tipoAtual?.id else null

        catalogo = novo

        // Cadastrar exige as duas permissões: a do operador (RFID/CREATE, vinda no login) e a do coletor (catálogo)
        val operadorPode = SessaoAtual.get(this)?.podeCadastrar == true

        when {
            !operadorPode -> aviso("Seu usuário não tem permissão para cadastrar etiquetas. Peça ao administrador a permissão de cadastro.")
            novo == null -> aviso("Catálogo ainda não baixado. Conecte o coletor ao servidor uma vez para poder cadastrar.")
            !novo.podeCadastrar -> aviso("Este coletor não tem permissão para cadastrar etiquetas. Peça ao administrador para liberá-lo no servidor.")
            else -> if (txtAviso.tag != AVISO_RECUSA) aviso(null)
        }
        btnSalvar.isEnabled = operadorPode && novo?.podeCadastrar == true

        val tipos = novo?.tipos.orEmpty()
        preencher(spnTipo, tipos.map { Opcao(it.id, it.nome) }, tipoSelecionado)
    }

    private fun configurarCascata() {
        spnTipo.onItemSelectedListener = aoSelecionar { opcao ->
            tipoAtual = catalogo?.tipos?.firstOrNull { it.id == opcao?.id }
            preencher(spnMarca, tipoAtual?.marcas.orEmpty().map { Opcao(it.id, it.nome) }, marcaPendente)
            marcaPendente = null
        }
        spnMarca.onItemSelectedListener = aoSelecionar { opcao ->
            marcaAtual = tipoAtual?.marcas?.firstOrNull { it.id == opcao?.id }
            preencher(spnModelo, marcaAtual?.modelos.orEmpty().map { Opcao(it.id, it.nome) }, modeloPendente)
            modeloPendente = null
        }
    }

    private fun preencher(spinner: Spinner, opcoes: List<Opcao>, selecionarId: Long?) {
        val lista = listOf(Opcao(null, "— não informado —")) + opcoes
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, lista)
        spinner.isEnabled = opcoes.isNotEmpty()
        spinner.setSelection(lista.indexOfFirst { it.id == selecionarId }.coerceAtLeast(0))
    }

    private fun aoSelecionar(acao: (Opcao?) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>, view: View?, posicao: Int, id: Long) {
            acao(parent.getItemAtPosition(posicao) as? Opcao)
        }

        override fun onNothingSelected(parent: AdapterView<*>) = acao(null)
    }

    // ================= CADASTRO =================

    /** Cadastro pendente ou recusado desta etiqueta: preenche para o operador revisar ou corrigir. */
    private fun preencherComCadastroGuardado() {
        repositorio.porEpc(epc) { guardado ->
            runOnUiThread {
                guardado ?: return@runOnUiThread

                edtCodigoInterno.setText(guardado.codigoInterno)
                edtPatrimonio.setText(guardado.patrimonio)
                edtNumeroSerie.setText(guardado.numeroSerie)
                marcaPendente = guardado.marcaId
                modeloPendente = guardado.modeloId
                preencher(spnTipo, catalogo?.tipos.orEmpty().map { Opcao(it.id, it.nome) }, guardado.tipoId)

                if (guardado.situacao == SituacaoCadastro.RECUSADO) {
                    aviso("O servidor recusou este cadastro: ${guardado.erro}. Corrija e salve de novo.")
                    txtAviso.tag = AVISO_RECUSA
                }
            }
        }
    }

    private fun salvar() {
        val codigoInterno = edtCodigoInterno.text.toString().trim()
        if (codigoInterno.isEmpty()) {
            edtCodigoInterno.error = "Informe o código interno"
            return
        }

        val tipo = spnTipo.selectedItem as? Opcao
        val marca = spnMarca.selectedItem as? Opcao
        val modelo = spnModelo.selectedItem as? Opcao

        val cadastro = CadastroEntity(
            epc = epc,
            codigoInterno = codigoInterno,
            patrimonio = edtPatrimonio.text.toString().trim(),
            numeroSerie = edtNumeroSerie.text.toString().trim(),
            tipoId = tipo?.id, tipoNome = tipo?.id?.let { tipo.nome },
            marcaId = marca?.id, marcaNome = marca?.id?.let { marca.nome },
            modeloId = modelo?.id, modeloNome = modelo?.id?.let { modelo.nome },
            criadoEm = 0 // o repositório usa a hora atual
        )

        // O servidor aceita cadastro parcial (como a web), mas só registra LEITURAS de etiqueta completa
        val faltando = listOfNotNull(
            "patrimônio".takeIf { cadastro.patrimonio.isEmpty() },
            "nº de série".takeIf { cadastro.numeroSerie.isEmpty() },
            "tipo".takeIf { cadastro.tipoId == null },
            "marca".takeIf { cadastro.marcaId == null },
            "modelo".takeIf { cadastro.modeloId == null }
        )

        if (faltando.isEmpty()) {
            confirmar(cadastro)
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Cadastro incompleto")
            .setMessage("Falta: ${faltando.joinToString()}.\n\nSem esses dados a etiqueta é cadastrada, mas não aparece nas leituras nem nos relatórios até ser completada na web.")
            .setPositiveButton("Salvar assim") { _, _ ->
                Log.i(TAG, "Operador salvou cadastro incompleto da etiqueta $epc (falta: ${faltando.joinToString()})")
                confirmar(cadastro)
            }
            .setNegativeButton("Completar", null)
            .show()
    }

    private fun confirmar(cadastro: CadastroEntity) {
        btnSalvar.isEnabled = false
        repositorio.salvar(cadastro) { guardado ->
            runOnUiThread {
                if (guardado) {
                    Toast.makeText(this, "Cadastro guardado: será enviado ao servidor", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    exigirSessao() // a sessão acabou enquanto o formulário estava aberto
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        exigirSessao()
    }

    private fun aviso(texto: String?) {
        txtAviso.text = texto
        txtAviso.visibility = if (texto == null) View.GONE else View.VISIBLE
    }

    companion object {
        const val EXTRA_EPC = "epc"
        private const val TAG = "CADASTRO"
        private const val AVISO_RECUSA = "recusa"
    }
}
