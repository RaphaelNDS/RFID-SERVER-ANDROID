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
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import com.google.android.material.textfield.TextInputLayout
import com.example.rfid_server.AppConfig
import com.example.rfid_server.CodigoDeBarras
import com.example.rfid_server.ConfigQr
import com.example.rfid_server.CodigoEtiqueta
import com.example.rfid_server.RfidManager
import com.example.rfid_server.R
import com.example.rfid_server.SessaoAtual
import com.example.rfid_server.SessaoOperador
import com.example.rfid_server.dados.CadastroEntity
import com.example.rfid_server.dados.CatalogoColetor
import com.example.rfid_server.dados.MarcaCatalogo
import com.example.rfid_server.dados.RepositorioCadastros
import com.example.rfid_server.dados.SituacaoCadastro
import com.example.rfid_server.dados.TipoCatalogo
import com.example.rfid_server.erros.MascaraSensivel
import com.example.rfid_server.service.RespostaServidor
import com.example.rfid_server.service.TagService
import com.google.android.material.appbar.MaterialToolbar
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
    private lateinit var btnGravarCodigo: Button
    private var gravando = false

    // Consultas do resultado do envio depois de salvar (ver aguardarEnvio)
    private val espera = Handler(Looper.getMainLooper())

    // ===== Sessão do operador (ver SessaoNoCadastro) =====
    // Avisa com 30 e 5 min e deixa renovar; renovada, volta a acompanhar a sessão nova
    // Sessão encerrada pelo servidor (ou vencida) com o formulário aberto: tratarSemSessao pede a senha aqui se
    // houver algo a perder; senão vai para o login como as outras telas
    private val avisoSessao by lazy {
        AvisoSessao(this, aoMudarSessao = { nova -> aoRenovarSessao(nova) }, aoSessaoAcabar = { tratarSemSessao(); true })
    }
    private val relogioSessao = Handler(Looper.getMainLooper())
    private val pararAvisoSessao = Runnable { avisoSessao.parar() }
    private val fimDaSessao = Runnable { if (SessaoAtual.get(this) == null) tratarSemSessao() }

    // Email do operador da sessão desta tela: depois de vencida a sessão é apagada, e a renovação precisa dele
    private var emailOperador: String? = null

    // Cadastro que não foi guardado porque a sessão tinha acabado: renovada a sessão, é salvo de novo
    private var cadastroEsperandoSessao: CadastroEntity? = null
    private var dialogoSenha: AlertDialog? = null

    private var catalogo: CatalogoColetor? = null
    private var tipoAtual: TipoCatalogo? = null
    private var marcaAtual: MarcaCatalogo? = null

    // Seleções a aplicar quando a cascata chegar no Spinner (prefill ou catálogo recarregado)
    private var marcaPendente: Long? = null
    private var modeloPendente: Long? = null

    // O que está salvo para esta etiqueta (vazio num cadastro novo): voltar com algo diferente pergunta antes
    private var formularioSalvo = FormularioCadastro()

    // Entre o Salvar e o resultado do envio: o catálogo que chega do servidor nesse meio-tempo não reabilita o botão
    // (antes ele voltava a ficar ativo com "Enviando…" e um segundo toque gravava o cadastro de novo)
    private var salvando = false
    // Tela recriada pelo Android (ex.: processo encerrado em segundo plano): os campos já voltaram com o que o
    // operador digitou e não são sobrescritos pelo cadastro guardado
    private var restaurada = false
    private var tipoRestaurado: Long? = null

    // Habilitado só com alterações não salvas: aí o voltar pergunta "Descartar o cadastro?"
    private val confirmarDescarte = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = perguntarDescarte()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cadastro_tag)

        // Etiqueta já regravada nesta tela (e a tela foi recriada): continua com o código novo
        epc = savedInstanceState?.getString(ESTADO_EPC_GRAVADO) ?: intent.getStringExtra(EXTRA_EPC) ?: run {
            Log.e(TAG, "Tela de cadastro aberta sem EPC")
            finish()
            return
        }
        repositorio = RepositorioCadastros.get(this)

        findViewById<TextView>(R.id.txtEpc).text = epc // o título da seção já diz "Etiqueta"
        btnGravarCodigo = findViewById(R.id.btnGravarCodigo)
        btnGravarCodigo.isVisible = !CodigoEtiqueta.daEmpresa(epc)
        btnGravarCodigo.setOnClickListener { if (CliqueUnico.permitir()) confirmarGravacao() }
        txtAviso = findViewById(R.id.txtAviso)
        edtCodigoInterno = findViewById(R.id.edtCodigoInterno)
        edtPatrimonio = findViewById(R.id.edtPatrimonio)
        edtNumeroSerie = findViewById(R.id.edtNumeroSerie)
        spnTipo = findViewById(R.id.spnTipo)
        spnMarca = findViewById(R.id.spnMarca)
        spnModelo = findViewById(R.id.spnModelo)
        btnSalvar = findViewById(R.id.btnSalvar)

        configurarCascata()
        // Código de barras: vai para o campo com foco; sem campo com foco, é a plaqueta de patrimônio
        CodigoDeBarras.ouvir(this) { codigo ->
            if (ConfigQr.ler(codigo) != null) return@ouvir true
            if (CodigoDeBarras.noCampoComFoco(this, codigo)) return@ouvir true
            edtPatrimonio.setText(codigo)
            true
        }
        btnSalvar.setOnClickListener { if (CliqueUnico.permitir()) salvar() }

        savedInstanceState?.let { estado ->
            restaurada = true
            tipoRestaurado = estado.idOuNull(ESTADO_TIPO)
            marcaPendente = estado.idOuNull(ESTADO_MARCA)
            modeloPendente = estado.idOuNull(ESTADO_MODELO)
        }

        onBackPressedDispatcher.addCallback(this, confirmarDescarte)
        // Seta da barra = botão voltar: com alteração não salva, pergunta "Descartar o cadastro?"
        findViewById<MaterialToolbar>(R.id.barraCadastro).setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
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
        val tipoSelecionado = if (manterSelecao) tipoAtual?.id else tipoRestaurado.also { tipoRestaurado = null }

        catalogo = novo

        // Cadastrar exige as duas permissões: a do operador (RFID/CREATE, vinda no login) e a do coletor (catálogo)
        val operadorPode = SessaoAtual.get(this)?.podeCadastrar == true

        when {
            !operadorPode -> aviso(getString(R.string.cadastro_operador_sem_permissao))
            novo == null -> aviso(getString(R.string.cadastro_catalogo_nao_baixado))
            !novo.podeCadastrar -> aviso(getString(R.string.cadastro_coletor_sem_permissao))
            else -> if (txtAviso.tag != AVISO_RECUSA) aviso(null)
        }
        btnSalvar.isEnabled = !salvando && operadorPode && novo?.podeCadastrar == true

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
                if (restaurada) {
                    // O que o operador tinha digitado já voltou nos campos: só recalcula se há alteração não salva
                    atualizarAlteracoes()
                } else {
                    preencherCampos(guardado)
                }

                if (guardado.situacao == SituacaoCadastro.RECUSADO) {
                    aviso(getString(R.string.cadastro_recusado, guardado.erro))
                    txtAviso.tag = AVISO_RECUSA
                }
            }
        }
    }

    private fun preencherCampos(guardado: CadastroEntity) {
        edtCodigoInterno.setText(guardado.codigoInterno)
        edtPatrimonio.setText(guardado.patrimonio)
        edtNumeroSerie.setText(guardado.numeroSerie)
        marcaPendente = guardado.marcaId
        modeloPendente = guardado.modeloId
        preencher(spnTipo, catalogo?.tipos.orEmpty().map { Opcao(it.id, it.nome) }, guardado.tipoId)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::spnTipo.isInitialized) return // aberta sem EPC: fechou no onCreate
        // Os campos de texto o Android guarda sozinho; a cascata Tipo → Marca → Modelo é remontada, então vai o id
        (spnTipo.selectedItem as? Opcao)?.id?.let { outState.putLong(ESTADO_TIPO, it) }
        (spnMarca.selectedItem as? Opcao)?.id?.let { outState.putLong(ESTADO_MARCA, it) }
        (spnModelo.selectedItem as? Opcao)?.id?.let { outState.putLong(ESTADO_MODELO, it) }
        if (epc != intent.getStringExtra(EXTRA_EPC)) outState.putString(ESTADO_EPC_GRAVADO, epc)
    }

    // ================= GRAVAR O CÓDIGO DA EMPRESA NA ETIQUETA =================

    private fun confirmarGravacao() {
        if (gravando) return
        val patrimonio = FormularioCadastro.normalizarCodigo(edtPatrimonio.text.toString())
        val novo = CodigoEtiqueta.gerar(patrimonio) ?: run {
            edtPatrimonio.error = getString(R.string.cadastro_gravar_falta_patrimonio)
            edtPatrimonio.requestFocus()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.cadastro_gravar_titulo)
            .setMessage(getString(R.string.cadastro_gravar_mensagem, epc, novo, patrimonio))
            .setPositiveButton(R.string.cadastro_gravar_confirmar) { _, _ -> gravar(novo) }
            .setNegativeButton(R.string.comum_cancelar, null)
            .show()
    }

    private fun gravar(novo: String) {
        val anterior = epc
        gravando = true
        btnGravarCodigo.isEnabled = false
        btnGravarCodigo.setText(R.string.cadastro_gravando)
        Log.i(TAG, "Operador pediu para gravar a etiqueta $anterior com o código da empresa $novo")

        RfidManager.get(this).gravarEpc(anterior, novo) { resultado ->
            runOnUiThread {
                gravando = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                btnGravarCodigo.isEnabled = true
                btnGravarCodigo.setText(R.string.cadastro_gravar_codigo)
                when (resultado) {
                    is RfidManager.ResultadoGravacao.Gravada -> {
                        // O cadastro passa a ser do código novo; a leitura antiga (não cadastrada) fica como estava
                        epc = resultado.epc
                        findViewById<TextView>(R.id.txtEpc).text = epc
                        btnGravarCodigo.isVisible = false
                        Toast.makeText(this, R.string.cadastro_gravada, Toast.LENGTH_LONG).show()
                    }
                    is RfidManager.ResultadoGravacao.Falhou -> aviso(resultado.mensagem)
                }
            }
        }
    }

    private fun salvar() {
        // Patrimônio, código interno e série em MAIÚSCULAS (ver FormularioCadastro.normalizarCodigo): o servidor compara
        // o patrimônio com igualdade exata, e o DataWedge/teclado físico gravam como vier
        val codigoInterno = FormularioCadastro.normalizarCodigo(edtCodigoInterno.text.toString())
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
            patrimonio = FormularioCadastro.normalizarCodigo(edtPatrimonio.text.toString()),
            numeroSerie = FormularioCadastro.normalizarCodigo(edtNumeroSerie.text.toString()),
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
        salvando = true
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
                    salvando = false
                    btnSalvar.isEnabled = true
                    // false = sessão acabou com o formulário aberto OU falha ao gravar (ex.: memória cheia, já avisada)
                    val temSessao = SessaoAtual.get(this) != null
                    when (SessaoNoCadastro.aposNaoGuardar(temSessao, emailParaRenovar())) {
                        SessaoNoCadastro.Acao.TENTAR_DE_NOVO ->
                            Log.w(TAG, "Cadastro da etiqueta ${cadastro.epc} não foi guardado no coletor: o operador pode tentar de novo")

                        SessaoNoCadastro.Acao.PEDIR_SENHA -> {
                            Log.i(TAG, "Sessão terminou antes de salvar o cadastro da etiqueta ${cadastro.epc}: pedindo a senha para salvar de novo")
                            cadastroEsperandoSessao = cadastro
                            pedirSenha()
                        }

                        SessaoNoCadastro.Acao.LOGIN -> exigirSessao()
                    }
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
                        salvando = false
                        btnSalvar.isEnabled = true
                        aviso(getString(R.string.cadastro_recusado, resultado.motivo))
                        txtAviso.tag = AVISO_RECUSA
                    }

                    is RepositorioCadastros.ResultadoEnvio.NaoEnviado -> {
                        // O motivo real (sem rede, erro do servidor, certificado...): antes dizia "Sem conexão" para tudo
                        val motivo = resultado.motivo?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
                        Log.i(TAG, "Cadastro de $epcGuardado guardado e não enviado agora: ${motivo ?: "sem motivo"}")
                        if (motivo == null) Toast.makeText(this, R.string.cadastro_sem_conexao, Toast.LENGTH_LONG).show()
                        else Toast.makeText(this, getString(R.string.cadastro_nao_enviado, motivo), Toast.LENGTH_LONG).show()
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
        relogioSessao.removeCallbacksAndMessages(null)
        // Tira o ouvinte global do fim de sessão (SessaoAtual) e o laço do Handler: sem isso a tela fechada vazava
        avisoSessao.parar()
        dialogoSenha?.dismiss()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val sessao = SessaoAtual.get(this) ?: return tratarSemSessao()
        emailOperador = sessao.email
        acompanharSessao(sessao)
    }

    override fun onPause() {
        relogioSessao.removeCallbacksAndMessages(null)
        avisoSessao.parar()
        super.onPause()
    }

    // ================= SESSÃO DO OPERADOR =================

    /**
     * Avisos de 30 e 5 min do AvisoSessao, que é parado um pouco antes do fim: depois do fim ele mandaria para o
     * login e o formulário se perderia. No fim, [tratarSemSessao] decide entre pedir a senha aqui ou ir para o login.
     */
    private fun acompanharSessao(sessao: SessaoOperador) {
        relogioSessao.removeCallbacksAndMessages(null)
        val agora = System.currentTimeMillis()
        val ateParar = SessaoNoCadastro.esperaAtePararAviso(sessao.expiraEm, agora)
        if (ateParar > 0) {
            avisoSessao.iniciar()
            relogioSessao.postDelayed(pararAvisoSessao, ateParar)
        } else {
            avisoSessao.parar()
        }
        relogioSessao.postDelayed(fimDaSessao, SessaoNoCadastro.esperaAteFim(sessao.expiraEm, agora))
    }

    private fun tratarSemSessao() {
        if (isFinishing || isDestroyed) return
        if (dialogoSenha?.isShowing == true) return
        val alterado = confirmarDescarte.isEnabled
        when (SessaoNoCadastro.semSessao(alterado, cadastroEsperandoSessao != null, emailParaRenovar())) {
            SessaoNoCadastro.Acao.PEDIR_SENHA -> {
                Log.i(TAG, "Sessão terminou com o cadastro da etiqueta $epc aberto e não salvo: pedindo a senha na própria tela")
                avisoSessao.parar()
                pedirSenha()
            }

            else -> exigirSessao()
        }
    }

    private fun emailParaRenovar(): String? =
        emailOperador ?: AppConfig.getUltimoEmail(this).takeIf { it.isNotBlank() }

    /**
     * Renovação da sessão vencida sem sair do cadastro: login de novo com a senha do mesmo operador.
     * Igual à renovação do AvisoSessao, que não tem como ser chamada daqui (o pedido de senha dele é privado e
     * exige a sessão ainda válida). Renovada, salva de novo o cadastro que estava esperando ([cadastroEsperandoSessao]).
     */
    private fun pedirSenha() {
        val email = emailParaRenovar() ?: run {
            exigirSessao()
            return
        }
        if (dialogoSenha?.isShowing == true) return

        // Mesmo campo do pedido de senha do AvisoSessao (dialogo_senha.xml)
        val vista = layoutInflater.inflate(R.layout.dialogo_senha, null)
        val campoSenha = vista.findViewById<EditText>(R.id.edtCampoDialogo)
        val mensagem = listOf(getString(R.string.cadastro_sessao_terminou), getString(R.string.sessao_senha_de, email))
            .joinToString("\n\n")

        // "Renovar" sem ouvinte aqui: tratado no onShow (abaixo) para o diálogo NÃO fechar ao tocar. Ele fica aberto
        // durante o login (até 15 s), com o botão em "Renovando…", e só fecha quando a sessão é renovada
        val dialogo = AlertDialog.Builder(this)
            .setTitle(R.string.sessao_renovar_titulo)
            .setMessage(mensagem)
            .setView(vista)
            .setCancelable(false)
            .setPositiveButton(R.string.sessao_renovar, null)
            .setNegativeButton(R.string.comum_cancelar) { _, _ ->
                Log.i(TAG, "Operador cancelou a renovação da sessão com o cadastro da etiqueta $epc aberto: o formulário continua na tela")
                cadastroEsperandoSessao = null
                Toast.makeText(this, R.string.cadastro_sessao_nao_renovada, Toast.LENGTH_LONG).show()
            }
            .create()
        dialogoSenha = dialogo

        // Concluir do teclado (ou Enter do teclado físico) = Renovar, só com o botão habilitado (não durante o login)
        CampoDialogo.confirmarPeloTeclado(dialogo, campoSenha)
        dialogo.setOnShowListener {
            CampoDialogo.focarComTeclado(dialogo, campoSenha)
            dialogo.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { renovar(email, campoSenha, dialogo) }
        }
        dialogo.show()
    }

    private fun renovar(email: String, campoSenha: EditText, dialogo: AlertDialog) {
        val senha = campoSenha.text.toString()
        if (senha.isEmpty()) {
            erroNoCampo(campoSenha, getString(R.string.sessao_informe_senha))
            return
        }

        val botao = dialogo.getButton(AlertDialog.BUTTON_POSITIVE)
        erroNoCampo(campoSenha, null)
        botao.isEnabled = false
        botao.setText(R.string.sessao_renovando)
        campoSenha.isEnabled = false

        Log.i(TAG, "Renovando a sessão de ${MascaraSensivel.email(email)} na tela de cadastro")
        TagService(this).entrar(email, senha) { resposta ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                botao.isEnabled = true
                botao.setText(R.string.sessao_renovar)
                campoSenha.isEnabled = true

                when (resposta) {
                    is RespostaServidor.Sucesso -> {
                        val nova = resposta.valor
                        dialogo.dismiss()
                        AppConfig.saveSessao(this, nova)
                        Log.i(TAG, "Sessão de ${MascaraSensivel.email(nova.email)} renovada no cadastro: vale até ${AvisoSessao.horario(nova.expiraEm)}")
                        Toast.makeText(this, getString(R.string.sessao_renovada, AvisoSessao.horario(nova.expiraEm)), Toast.LENGTH_LONG).show()
                        aoRenovarSessao(nova)
                    }

                    // O diálogo continua aberto, com o motivo no campo, para tentar de novo
                    is RespostaServidor.Falha -> {
                        Log.w(TAG, "Renovação da sessão de ${MascaraSensivel.email(email)} recusada no cadastro: ${resposta.mensagem}")
                        campoSenha.text = null
                        erroNoCampo(campoSenha, resposta.mensagem)
                    }

                    RespostaServidor.NaoEncontrado -> erroNoCampo(campoSenha, getString(R.string.comum_servidor_nao_reconheceu_login))
                }
            }
        }
    }

    /** Mostra (ou tira, com null) o erro no TextInputLayout em volta do [campo] (no próprio campo, se não houver). */
    private fun erroNoCampo(campo: EditText, erro: String?) {
        var pai = campo.parent
        while (pai != null && pai !is TextInputLayout) pai = pai.parent
        val layout = pai as? TextInputLayout
        if (layout != null) layout.error = erro else campo.error = erro
        if (erro != null) campo.requestFocus()
    }

    /** Sessão renovada (aqui ou pelo AvisoSessao): volta a acompanhar e salva o cadastro que estava esperando. */
    private fun aoRenovarSessao(nova: SessaoOperador) {
        emailOperador = nova.email
        // Só com a tela na frente: em pausa (ex.: a resposta do login chegou com outra tela por cima) o AvisoSessao e o
        // relógio da sessão ficariam rodando escondidos; o onResume volta a acompanhar a sessão nova
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) acompanharSessao(nova)

        val pendente = cadastroEsperandoSessao ?: return
        cadastroEsperandoSessao = null
        if (!nova.podeCadastrar || catalogo?.podeCadastrar != true) {
            // A permissão pode ter mudado no servidor: mostra o motivo e desliga o Salvar
            Log.w(TAG, "Sessão renovada, mas o cadastro da etiqueta ${pendente.epc} não pode ser salvo (sem permissão)")
            aplicarCatalogo(catalogo, manterSelecao = true)
            return
        }
        Log.i(TAG, "Sessão renovada: salvando de novo o cadastro da etiqueta ${pendente.epc}")
        confirmar(pendente)
    }

    private fun aviso(texto: String?) {
        txtAviso.text = texto
        txtAviso.visibility = if (texto == null) View.GONE else View.VISIBLE
    }

    companion object {
        const val EXTRA_EPC = "epc"
        private const val TAG = "CADASTRO"
        private const val AVISO_RECUSA = "recusa"
        private const val ESTADO_TIPO = "tipo"
        private const val ESTADO_MARCA = "marca"
        private const val ESTADO_MODELO = "modelo"
        private const val ESTADO_EPC_GRAVADO = "epc_gravado"

        private fun Bundle.idOuNull(chave: String): Long? = if (containsKey(chave)) getLong(chave) else null
        private const val ESPERA_ENVIO_MS = 5_000L
        private const val INTERVALO_CONSULTA_MS = 300L
    }
}
