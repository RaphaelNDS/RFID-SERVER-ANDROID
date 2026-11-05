package com.example.rfid_server.activities

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
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

    // Consultas do resultado do envio depois de salvar (ver aguardarEnvio)
    private val espera = Handler(Looper.getMainLooper())

    private var catalogo: CatalogoColetor? = null
    private var tipoAtual: TipoCatalogo? = null
    private var marcaAtual: MarcaCatalogo? = null

    // Seleções a aplicar quando a cascata chegar no Spinner (prefill ou catálogo recarregado)
    private var marcaPendente: Long? = null
    private var modeloPendente: Long? = null

    // O que está salvo para esta etiqueta (vazio num cadastro novo): voltar com algo diferente pergunta antes
    private var formularioSalvo = FormularioCadastro()

    // Habilitado só com alterações não salvas: aí o voltar pergunta "Descartar o cadastro?"
    private val confirmarDescarte = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = perguntarDescarte()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cadastro_tag)

        epc = intent.getStringExtra(EXTRA_EPC) ?: run {
            Log.e(TAG, "Tela de cadastro aberta sem EPC")
            finish()
            return
        }
        repositorio = RepositorioCadastros.get(this)

        findViewById<TextView>(R.id.txtEpc).text = epc // o título da seção já diz "Etiqueta"
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

        onBackPressedDispatcher.addCallback(this, confirmarDescarte)
        listOf(edtCodigoInterno, edtPatrimonio, edtNumeroSerie).forEach { campo ->
            campo.doAfterTextChanged { atualizarAlteracoes() }
        }

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
            !operadorPode -> aviso(getString(R.string.cadastro_operador_sem_permissao))
            novo == null -> aviso(getString(R.string.cadastro_catalogo_nao_baixado))
            !novo.podeCadastrar -> aviso(getString(R.string.cadastro_coletor_sem_permissao))
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
            atualizarAlteracoes()
        }
        spnMarca.onItemSelectedListener = aoSelecionar { opcao ->
            marcaAtual = tipoAtual?.marcas?.firstOrNull { it.id == opcao?.id }
            preencher(spnModelo, marcaAtual?.modelos.orEmpty().map { Opcao(it.id, it.nome) }, modeloPendente)
            modeloPendente = null
            atualizarAlteracoes()
        }
        spnModelo.onItemSelectedListener = aoSelecionar { atualizarAlteracoes() }
    }

    // ================= ALTERAÇÕES NÃO SALVAS =================

    private fun formularioAtual() = FormularioCadastro(
        codigoInterno = edtCodigoInterno.text.toString(),
        patrimonio = edtPatrimonio.text.toString(),
        numeroSerie = edtNumeroSerie.text.toString(),
        tipoId = (spnTipo.selectedItem as? Opcao)?.id,
        marcaId = (spnMarca.selectedItem as? Opcao)?.id,
        modeloId = (spnModelo.selectedItem as? Opcao)?.id
    )

    private fun atualizarAlteracoes() {
        // Tipo/marca/modelo guardados que não existem no catálogo do coletor (ex.: modelo removido) não podem ser
        // selecionados e a lista fica em "não informado": isso não é alteração do operador
        val salvo = formularioSalvo.copy(
            tipoId = formularioSalvo.tipoId?.takeIf { temOpcao(spnTipo, it) },
            marcaId = formularioSalvo.marcaId?.takeIf { temOpcao(spnMarca, it) },
            modeloId = formularioSalvo.modeloId?.takeIf { temOpcao(spnModelo, it) }
        )
        confirmarDescarte.isEnabled = formularioAtual().alteradoEmRelacaoA(salvo)
    }

    private fun temOpcao(spinner: Spinner, id: Long): Boolean =
        (0 until spinner.count).any { (spinner.getItemAtPosition(it) as? Opcao)?.id == id }

    private fun perguntarDescarte() {
        AlertDialog.Builder(this)
            .setTitle(R.string.cadastro_descartar_titulo)
            .setMessage(R.string.cadastro_descartar_mensagem)
            .setPositiveButton(R.string.cadastro_descartar) { _, _ ->
                Log.i(TAG, "Operador descartou as alterações não salvas do cadastro da etiqueta $epc")
                confirmarDescarte.isEnabled = false
                finish()
            }
            .setNegativeButton(R.string.cadastro_continuar_editando, null)
            .show()
    }

    private fun preencher(spinner: Spinner, opcoes: List<Opcao>, selecionarId: Long?) {
        val lista = listOf(Opcao(null, getString(R.string.cadastro_nao_informado))) + opcoes
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

                // Antes do setText: os campos preenchidos com o que já está salvo não contam como alteração
                formularioSalvo = FormularioCadastro(
                    guardado.codigoInterno, guardado.patrimonio, guardado.numeroSerie,
                    guardado.tipoId, guardado.marcaId, guardado.modeloId
                )
                edtCodigoInterno.setText(guardado.codigoInterno)
                edtPatrimonio.setText(guardado.patrimonio)
                edtNumeroSerie.setText(guardado.numeroSerie)
                marcaPendente = guardado.marcaId
                modeloPendente = guardado.modeloId
                preencher(spnTipo, catalogo?.tipos.orEmpty().map { Opcao(it.id, it.nome) }, guardado.tipoId)

                if (guardado.situacao == SituacaoCadastro.RECUSADO) {
                    aviso(getString(R.string.cadastro_recusado, guardado.erro))
                    txtAviso.tag = AVISO_RECUSA
                }
            }
        }
    }

    private fun salvar() {
        val codigoInterno = edtCodigoInterno.text.toString().trim()
        if (codigoInterno.isEmpty()) {
            edtCodigoInterno.error = getString(R.string.cadastro_informe_codigo_interno)
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
            R.string.cadastro_falta_patrimonio.takeIf { cadastro.patrimonio.isEmpty() },
            R.string.cadastro_falta_numero_serie.takeIf { cadastro.numeroSerie.isEmpty() },
            R.string.cadastro_falta_tipo.takeIf { cadastro.tipoId == null },
            R.string.cadastro_falta_marca.takeIf { cadastro.marcaId == null },
            R.string.cadastro_falta_modelo.takeIf { cadastro.modeloId == null }
        ).map { getString(it) }

        if (faltando.isEmpty()) {
            confirmar(cadastro)
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.cadastro_incompleto_titulo)
            .setMessage(getString(R.string.cadastro_incompleto_mensagem, faltando.joinToString()))
            .setPositiveButton(R.string.cadastro_salvar_assim) { _, _ ->
                Log.i(TAG, "Operador salvou cadastro incompleto da etiqueta $epc (falta: ${faltando.joinToString()})")
                confirmar(cadastro)
            }
            .setNegativeButton(R.string.cadastro_completar, null)
            .show()
    }

    private fun confirmar(cadastro: CadastroEntity) {
        btnSalvar.isEnabled = false
        repositorio.salvar(cadastro) { guardado ->
            runOnUiThread {
                if (guardado) {
                    // Já está guardado no coletor: voltar agora não perde nada
                    formularioSalvo = FormularioCadastro(
                        cadastro.codigoInterno, cadastro.patrimonio, cadastro.numeroSerie,
                        cadastro.tipoId, cadastro.marcaId, cadastro.modeloId
                    )
                    atualizarAlteracoes()
                    btnSalvar.setText(R.string.cadastro_enviando)
                    aguardarEnvio(cadastro.epc.trim(), SystemClock.elapsedRealtime())
                } else {
                    // A sessão acabou enquanto o formulário estava aberto (vai para o login) ou não deu para gravar
                    // (ex.: memória cheia, já avisado): libera o botão para tentar de novo
                    btnSalvar.isEnabled = true
                    exigirSessao()
                }
            }
        }
    }

    /**
     * O cadastro já está guardado no coletor e o Sincronizador tenta enviar na hora. Espera o resultado
     * por até [ESPERA_ENVIO_MS] para dizer ao operador o que aconteceu de fato; passado isso, o envio
     * continua sozinho em segundo plano.
     */
    private fun aguardarEnvio(epcGuardado: String, inicio: Long) {
        repositorio.porEpc(epcGuardado) { atual ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread

                when (val resultado = RepositorioCadastros.resultadoEnvio(atual)) {
                    RepositorioCadastros.ResultadoEnvio.Enviado -> {
                        Toast.makeText(this, R.string.cadastro_enviado, Toast.LENGTH_LONG).show()
                        finish()
                    }

                    // Fica no formulário com o motivo, para corrigir e salvar de novo
                    is RepositorioCadastros.ResultadoEnvio.Recusado -> {
                        btnSalvar.setText(R.string.cadastro_salvar) // o mesmo do layout
                        btnSalvar.isEnabled = true
                        aviso(getString(R.string.cadastro_recusado, resultado.motivo))
                        txtAviso.tag = AVISO_RECUSA
                    }

                    is RepositorioCadastros.ResultadoEnvio.NaoEnviado -> {
                        Toast.makeText(this, R.string.cadastro_sem_conexao, Toast.LENGTH_LONG).show()
                        finish()
                    }

                    RepositorioCadastros.ResultadoEnvio.Aguardando ->
                        if (SystemClock.elapsedRealtime() - inicio >= ESPERA_ENVIO_MS) {
                            Log.i(TAG, "Envio do cadastro de $epcGuardado não terminou em ${ESPERA_ENVIO_MS / 1000} s: segue em segundo plano")
                            Toast.makeText(this, R.string.cadastro_guardado, Toast.LENGTH_LONG).show()
                            finish()
                        } else {
                            espera.postDelayed({ aguardarEnvio(epcGuardado, inicio) }, INTERVALO_CONSULTA_MS)
                        }
                }
            }
        }
    }

    override fun onDestroy() {
        espera.removeCallbacksAndMessages(null)
        super.onDestroy()
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
        private const val ESPERA_ENVIO_MS = 5_000L
        private const val INTERVALO_CONSULTA_MS = 300L
    }
}
