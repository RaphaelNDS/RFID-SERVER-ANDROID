package com.example.rfid_server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.rfid_server.erros.RegistroErros
import com.example.rfid_server.erros.TipoErro
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.zebra.rfid.api3.ENUM_TRANSPORT
import com.zebra.rfid.api3.ENUM_TRIGGER_MODE
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE
import com.zebra.rfid.api3.InvalidUsageException
import com.zebra.rfid.api3.MEMORY_BANK
import com.zebra.rfid.api3.OperationFailureException
import com.zebra.rfid.api3.RFIDReader
import com.zebra.rfid.api3.RFIDResults
import com.zebra.rfid.api3.Readers
import com.zebra.rfid.api3.RfidEventsListener
import com.zebra.rfid.api3.RfidReadEvents
import com.zebra.rfid.api3.RfidStatusEvents
import com.zebra.rfid.api3.STATUS_EVENT_TYPE
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Situação da conexão com o leitor RFID, mostrada no topo das telas de leitura. */
enum class EstadoLeitor(val descricao: String) {
    CONECTANDO("Conectando ao leitor RFID..."),
    CONECTADO("Leitor RFID conectado"),
    DESCONECTADO("Leitor RFID desconectado: tentando de novo"),
    /** Desconectado sem tentativa automática (nenhuma tela de leitura aberta): só o botão "Reconectar" ou abrir uma tela de leitura. */
    PARADO("Leitor RFID desconectado")
}

/**
 * Gerenciador ÚNICO do leitor RFID Zebra para o app inteiro.
 *
 * Antes, cada tela criava a própria conexão: a tela principal continuava conectada (e registrando
 * leituras) enquanto a de localização abria outra. Agora há uma conexão só, e as leituras vão
 * apenas para a tela visível: ela se registra no onResume ([definirOuvinte]) e sai no onPause
 * ([removerOuvinte]).
 *
 * Enquanto há uma tela de leitura visível, o leitor é conectado sozinho e, se cair (bateria, encaixe,
 * Bluetooth), reconectado sozinho em intervalos ([ReconexaoLeitor]). A situação fica em [estado].
 * Sem nenhuma tela de leitura por [ReconexaoLeitor.DESCONECTAR_OCIOSO_MS], o leitor é desconectado.
 *
 * Conectar e desconectar rodam numa thread própria: o SDK da Zebra pode demorar segundos, e na
 * thread principal isso travaria a tela (ANR). Tudo que mexe na conexão roda nessa thread.
 */
class RfidManager private constructor(private val appContext: Context) : RfidEventsListener {

    /** Recebe EPC e RSSI. Chamado na thread do SDK da Zebra (use runOnUiThread para mexer na tela). */
    fun interface OuvinteLeitura {
        fun aoLer(epc: String, rssi: Int)

        /** Modo localização: distância relativa da etiqueta procurada, de 0 (longe) a 100 (encostada). */
        fun aoLocalizar(epc: String, distancia: Int) {}

        /**
         * Esta tela aceita o filtro "só etiquetas da empresa"? Só o Inventário: na Leitura as etiquetas de fábrica
         * precisam aparecer (é por lá que se cadastra e grava o código da empresa nelas).
         */
        val usaFiltroEmpresa: Boolean get() = false
    }

    private val executor = Executors.newSingleThreadScheduledExecutor()

    private var readers: Readers? = null

    // Trocado no executor, mas lido também na thread principal (conectado) e nas threads de eventos do SDK
    @Volatile
    private var reader: RFIDReader? = null

    // Só acessados na thread do executor
    private var tentativasSemSucesso = 0
    private var proximaTentativa: ScheduledFuture<*>? = null

    // Motivo da última falha de conexão (vai para a faixa e, na primeira falha de cada sequência, para o servidor)
    private var motivoFalhaConexao = MotivoFalhaLeitor(CausaFalhaLeitor.DESCONHECIDA, "motivo desconhecido")
    private var erroFalhaConexao: Throwable? = null

    // Queda do leitor (evento de desconexão): quando foi e quantas tentativas de conexão desde então, para registrar a volta
    private var inicioQueda: Long? = null
    private var tentativasDesdeQueda = 0

    // No máximo um registro por minuto de cada tipo de falha (o operador pode apertar o gatilho várias vezes seguidas)
    private val agrupadorFalhaLeitura = AgrupadorFalhas()
    private val agrupadorFalhaConfiguracao = AgrupadorFalhas()
    private val agrupadorFalhaEvento = AgrupadorFalhas()
    private val agrupadorVolta = AgrupadorFalhas()

    // Bip de erro do leitor: um só para o app inteiro (o gatilho chega mesmo sem a tela ter um AvisoLeitura próprio)
    private val avisoErro by lazy { AvisoLeitura(appContext) }

    @Volatile
    private var dono: Any? = null

    @Volatile
    private var ouvinte: OuvinteLeitura? = null

    @Volatile
    private var locatingEpc: String? = null

    // O gatilho ligou a localização (para o "solto" desligar a mesma operação que foi ligada)
    @Volatile
    private var localizandoComGatilho = false

    private val _estado = MutableLiveData(EstadoLeitor.PARADO)
    val estado: LiveData<EstadoLeitor> = _estado

    val conectado: Boolean
        get() = reader?.isConnected == true

    // Motivo da desconexão, falhas do gatilho/configuração e bateria: o que a faixa mostra além do [estado]
    private val travaDetalhe = Any()

    @Volatile
    private var detalheAtual = DetalheLeitor()
    // Gatilho apertado agora: as telas de leitura mantêm a tela acesa enquanto lê e por 2 min depois (TelaAcesa)
    private val _gatilhoApertado = MutableLiveData(false)
    val gatilhoApertado: LiveData<Boolean> = _gatilhoApertado

    private val _detalhe = MutableLiveData(DetalheLeitor())
    val detalhe: LiveData<DetalheLeitor> = _detalhe

    private fun atualizarDetalhe(mudanca: (DetalheLeitor) -> DetalheLeitor) {
        synchronized(travaDetalhe) {
            val novo = mudanca(detalheAtual)
            if (novo == detalheAtual) return
            detalheAtual = novo
            _detalhe.postValue(novo)
        }
    }

    // ================= CONEXÃO =================

    /** Botão "Reconectar": tenta agora, sem esperar a próxima tentativa automática. */
    fun reconectarAgora() {
        executor.execute {
            Log.i(TAG, "Reconexão do leitor RFID pedida pelo operador")
            cancelarProximaTentativa()
            // Conectado mas com o gatilho ou a potência sem configurar: "conectado" não reconfiguraria nada (a falha
            // ficava na faixa e o gatilho não lia). Solta o leitor para a tentativa conectar e configurar do zero
            if (conectado && detalheAtual.falhaConfiguracao != null) {
                Log.i(TAG, "Leitor RFID conectado com falha na configuração: reconectando do zero")
                liberarSdk()
            }
            tentativasSemSucesso = 0
            tentarConectar()
        }
    }

    // Roda na thread do executor
    private fun tentarConectar() {
        proximaTentativa = null
        if (conectado) {
            atualizarEstado(EstadoLeitor.CONECTADO)
            desconectarSeOcioso()
            return
        }

        atualizarEstado(EstadoLeitor.CONECTANDO)
        if (inicioQueda != null) tentativasDesdeQueda++

        if (conectarAgora()) {
            if (tentativasSemSucesso > 0) Log.i(TAG, "Leitor RFID reconectado depois de $tentativasSemSucesso tentativa(s) sem sucesso")
            tentativasSemSucesso = 0
            registrarVoltaDaQueda()
            atualizarDetalhe { it.copy(motivoDesconexao = null) }
            atualizarEstado(EstadoLeitor.CONECTADO)
            desconectarSeOcioso()
            return
        }

        tentativasSemSucesso++
        // Primeira falha de uma sequência: registra para a tela de erros do coletor no servidor (não a cada tentativa)
        if (tentativasSemSucesso == 1) {
            RegistroErros.registrar(appContext, TipoErro.LEITOR_RFID, "Leitor RFID não conectou: ${descricaoLog(motivoFalhaConexao)}", erroFalhaConexao)
        }
        val motivo = motivoFalhaConexao
        atualizarDetalhe { it.copy(motivoDesconexao = motivo) }
        atualizarEstado(estadoDesconectado())
        agendarNovaTentativa(ReconexaoLeitor.esperaMs(tentativasSemSucesso))
    }

    // Roda na thread do executor. O leitor tinha caído e voltou: o servidor fica sabendo da queda e de quanto durou
    private fun registrarVoltaDaQueda() {
        val inicio = inicioQueda ?: return
        val mensagem = ReconexaoLeitor.mensagemVolta(SystemClock.elapsedRealtime() - inicio, tentativasDesdeQueda.coerceAtLeast(1))
        inicioQueda = null
        tentativasDesdeQueda = 0
        Log.i(TAG, mensagem)
        registrarAgrupado(agrupadorVolta, mensagem, null)
    }

    // Roda na thread do executor. "Reconectar" numa tela que não lê (sem ouvinte) conectou o leitor: sem isto
    // ele ficaria ligado em segundo plano para sempre, gastando bateria
    private fun desconectarSeOcioso() {
        if (ouvinte == null) agendarDesconexaoOciosa()
    }

    // Sem tela de leitura não há nova tentativa automática: a faixa não pode dizer "tentando de novo"
    private fun estadoDesconectado(): EstadoLeitor =
        if (ouvinte == null) EstadoLeitor.PARADO else EstadoLeitor.DESCONECTADO

    // Roda na thread do executor. Só tenta de novo se houver uma tela esperando leituras
    private fun agendarNovaTentativa(esperaMs: Long) {
        if (ouvinte == null) {
            Log.d(TAG, "Leitor RFID sem conexão e nenhuma tela de leitura aberta: reconexão adiada até uma tela abrir")
            return
        }
        if (proximaTentativa != null) return

        if (ReconexaoLeitor.deveRegistrar(tentativasSemSucesso)) {
            Log.w(TAG, "Leitor RFID sem conexão (tentativa $tentativasSemSucesso): nova tentativa em ${esperaMs / 1000} s")
        }
        proximaTentativa = executor.schedule({ tentarConectar() }, esperaMs, TimeUnit.MILLISECONDS)
    }

    private fun cancelarProximaTentativa() {
        proximaTentativa?.cancel(false)
        proximaTentativa = null
    }

    private fun conectarAgora(): Boolean {
        return try {
            Log.i(TAG, "Conectando ao leitor RFID...")

            // Uma tentativa anterior pode ter deixado o SDK pela metade
            liberarSdk()

            readers = Readers(appContext, ENUM_TRANSPORT.ALL)
            val lista = readers?.GetAvailableRFIDReaderList()

            if (lista.isNullOrEmpty()) {
                motivoFalhaConexao = MotivoFalhaLeitor(CausaFalhaLeitor.NENHUM_LEITOR, "nenhum leitor RFID encontrado")
                erroFalhaConexao = null
                if (ReconexaoLeitor.deveRegistrar(tentativasSemSucesso + 1)) Log.e(TAG, "Nenhum leitor RFID encontrado")
                return false
            }

            val r = lista[0].rfidReader
            reader = r

            if (!r.isConnected) r.connect()

            configurarLeitor(r)
            acompanharBateriaDoAparelho()
            Log.i(TAG, "Leitor RFID conectado: ${lista[0].name}")
            true

        } catch (e: Exception) {
            motivoFalhaConexao = motivoDe(e)
            erroFalhaConexao = e
            if (ReconexaoLeitor.deveRegistrar(tentativasSemSucesso + 1)) Log.e(TAG, "Erro ao conectar no leitor RFID", e)
            // Falhou depois do connect() (ex.: ao ligar os eventos): sem soltar, a próxima tentativa veria "conectado",
            // mostraria a faixa verde e o gatilho ficaria sem ouvinte
            liberarSdk()
            false
        }
    }

    private fun configurarLeitor(r: RFIDReader) {
        r.Events.addEventsListener(this)
        r.Events.setHandheldEvent(true)
        r.Events.setTagReadEvent(true)
        r.Events.setAttachTagDataWithReadEvent(true)
        r.Events.setReaderDisconnectEvent(true)

        // Leitores com bateria própria (ex.: RFD40/RFD8500) mandam o nível por evento. O do MC3300x é interno e usa a
        // bateria do coletor: pode não mandar nada, e aí vale a bateria do aparelho (acompanharBateriaDoAparelho)
        try {
            r.Events.setBatteryEvent(true)
        } catch (e: Exception) {
            Log.d(TAG, "Leitor sem evento de bateria: ${e.message}")
        }

        // Potência e gatilho separados: antes, uma falha na potência deixava o gatilho sem configurar
        var falha: FalhaConfiguracao? = null
        var erro: Exception? = null
        try {
            definirPotencia(r)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar a potência do leitor RFID", e)
            falha = FalhaConfiguracao(ParteConfiguracao.POTENCIA, motivoDe(e))
            erro = e
        }
        try {
            // Leitura RFID SOMENTE no gatilho; com uma tela de código de barras aberta, o gatilho fica com o leitor de códigos
            r.Config.setTriggerMode(modoGatilho(), true)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar o gatilho do leitor RFID", e)
            // O gatilho é o mais grave (sem ele a leitura não começa): é o que a faixa mostra
            falha = FalhaConfiguracao(ParteConfiguracao.GATILHO, motivoDe(e))
            erro = e
        }

        val resultado = falha
        atualizarDetalhe { it.copy(falhaConfiguracao = resultado) }
        if (resultado != null) {
            val parte = if (resultado.parte == ParteConfiguracao.GATILHO) "o gatilho" else "a potência"
            registrarAgrupado(
                agrupadorFalhaConfiguracao,
                "Leitor RFID conectado, mas não foi possível configurar $parte: ${descricaoLog(resultado.motivo)}", erro
            )
        }
    }

    /** Potência escolhida nas Configurações (% da máxima). Menos potência: lê só o que está perto e gasta menos bateria. */
    private fun definirPotencia(r: RFIDReader) {
        val percentual = AppConfig.getPotenciaLeitor(appContext)
        val niveis = r.ReaderCapabilities.transmitPowerLevelValues
        val indice = ReconexaoLeitor.indicePotencia(niveis.size, percentual)

        val antConfig = r.Config.Antennas.getAntennaRfConfig(1)
        antConfig.transmitPowerIndex = indice
        r.Config.Antennas.setAntennaRfConfig(1, antConfig)
        Log.i(TAG, "Potência do leitor: $percentual% (nível ${indice + 1} de ${niveis.size}, valor ${niveis.getOrNull(indice)})")
    }

    // Filtros das leituras (Configurações), em memória: são consultados a cada etiqueta lida, na thread do SDK
    @Volatile
    private var rssiMinimo: Int? = AppConfig.getRssiMinimo(appContext)
    @Volatile
    private var soEtiquetasEmpresa: Boolean = AppConfig.isSoEtiquetasEmpresa(appContext)

    /** As Configurações mudaram os filtros da leitura: valem já, na próxima etiqueta lida. */
    fun aplicarFiltros() {
        rssiMinimo = AppConfig.getRssiMinimo(appContext)
        soEtiquetasEmpresa = AppConfig.isSoEtiquetasEmpresa(appContext)
        Log.i(TAG, "Filtros da leitura: alcance ${rssiMinimo?.let { "mínimo $it dBm" } ?: "sem limite"}, " +
            if (soEtiquetasEmpresa) "só etiquetas da empresa (${FiltroLeitura.PREFIXO_EMPRESA}…)" else "todas as etiquetas")
    }


    // ================= GATILHO: RFID OU CÓDIGO DE BARRAS =================

    // No MC3300xR o mesmo gatilho serve ao RFID e ao leitor de código de barras, e o modo RFID fica gravado no leitor:
    // sem voltar ao modo de código de barras, o gatilho não lia o QR de configuração nas Configurações
    @Volatile
    private var gatilhoNoCodigoDeBarras = false

    private fun modoGatilho(): ENUM_TRIGGER_MODE =
        if (gatilhoNoCodigoDeBarras) ENUM_TRIGGER_MODE.BARCODE_MODE else ENUM_TRIGGER_MODE.RFID_MODE

    /**
     * Tela que lê código de barras com o gatilho (Configurações, para o QR de configuração): [ligado] = o gatilho vai
     * para o leitor de código de barras enquanto ela está aberta; false = volta ao RFID. Sem o leitor conectado, conecta
     * só para mudar o modo (e solta depois, como sempre que nenhuma tela lê RFID).
     */
    fun gatilhoParaCodigoDeBarras(ligado: Boolean) {
        gatilhoNoCodigoDeBarras = ligado
        executor.execute {
            val r = reader?.takeIf { it.isConnected }
            if (r == null) {
                // Para voltar ao RFID não precisa conectar: a próxima conexão de uma tela de leitura configura o RFID
                if (ligado) tentarConectar()
                return@execute
            }
            try {
                r.Config.setTriggerMode(modoGatilho(), true)
                Log.i(TAG, "Gatilho do coletor no ${if (ligado) "leitor de código de barras" else "RFID"}")
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao mudar o gatilho para o ${if (ligado) "código de barras" else "RFID"}", e)
            }
        }
    }
    /** As Configurações mudaram a potência: aplica já, se o leitor estiver conectado (senão, vale na próxima conexão). */
    fun aplicarPotencia() {
        executor.execute {
            val r = reader?.takeIf { it.isConnected } ?: return@execute
            try {
                definirPotencia(r)
                // A potência voltou a valer: o aviso de falha dela sai da faixa
                atualizarDetalhe { d -> d.takeIf { it.falhaConfiguracao?.parte == ParteConfiguracao.POTENCIA }?.copy(falhaConfiguracao = null) ?: d }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao mudar a potência do leitor", e)
            }
        }
    }

    /** Desconecta em segundo plano. Chame só quando o app estiver saindo de vez. */
    fun desconectar() {
        executor.execute {
            cancelarProximaTentativa()
            cancelarDesconexaoOciosa()
            tentativasSemSucesso = 0
            inicioQueda = null
            liberarSdk()
            atualizarDetalhe { it.copy(motivoDesconexao = null) }
            atualizarEstado(EstadoLeitor.PARADO)
            Log.i(TAG, "Leitor RFID desconectado")
        }
    }

    private fun liberarSdk() {
        pararDeAcompanharBateriaDoAparelho()
        // Próxima conexão (pode ser outro leitor) volta a aceitar a bateria do aparelho até o SDK mandar o evento
        bateriaPeloSdk = false
        try {
            reader?.Events?.removeEventsListener(this)
            if (reader?.isConnected == true) {
                // O modo RFID fica gravado no leitor: sem isto, com o app sem ler (tela de login, Configurações, outro
                // app), o gatilho do coletor não lia código de barras
                try {
                    reader?.Config?.setTriggerMode(ENUM_TRIGGER_MODE.BARCODE_MODE, true)
                } catch (e: Exception) {
                    Log.w(TAG, "Não foi possível devolver o gatilho ao leitor de código de barras ao soltar o leitor RFID: ${e.message}")
                }
                reader?.disconnect()
            }
            readers?.Dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desconectar do leitor RFID", e)
        } finally {
            reader = null
            readers = null
            // Sem leitor, não há configuração falha para mostrar (a próxima conexão configura de novo)
            atualizarDetalhe { it.copy(falhaConfiguracao = null) }
        }
    }

    // ================= GRAVAÇÃO DA ETIQUETA =================

    /** Resultado de [gravarEpc]: [mensagem] para o operador quando não deu certo. */
    sealed class ResultadoGravacao {
        data class Gravada(val epc: String) : ResultadoGravacao()
        /** Não gravou e a etiqueta não mudou (ex.: leitor desconectado). */
        data class Falhou(val mensagem: String) : ResultadoGravacao()
        /** Erro no meio da escrita ou conferência que não confirmou: a etiqueta pode ter mudado; cadastrar só depois de reler. */
        data class Incerta(val mensagem: String) : ResultadoGravacao()
    }

    /**
     * Grava [novo] (24 hexadecimais, ver CodigoEtiqueta) na etiqueta que hoje responde com [atual], e confere relendo.
     * Escreve a palavra PC (tamanho de 96 bits) junto com o EPC: uma etiqueta que vinha com EPC de 128 bits também
     * passa a responder só com os 96 novos. Sem trava (decisão de 09/11): dá para regravar se o cadastro estiver errado.
     * Conecta o leitor se preciso. [aoTerminar] roda na thread do leitor.
     */
    fun gravarEpc(atual: String, novo: String, aoTerminar: (ResultadoGravacao) -> Unit) {
        executor.execute {
            val resultado = try {
                if (!conectado) tentarConectar()
                val r = reader?.takeIf { it.isConnected }
                if (r == null) {
                    ResultadoGravacao.Falhou("Leitor RFID desconectado: encaixe o leitor e tente de novo")
                } else {
                    gravarNoLeitor(r, atual.uppercase(), novo.uppercase())
                }
            } catch (e: Exception) {
                val motivo = motivoDe(e)
                Log.e(TAG, "Falha ao gravar a etiqueta $atual com $novo: ${descricaoLog(motivo)}", e)
                // A escrita pode ter gravado parte do código: a etiqueta talvez já não responda pelo código antigo
                ResultadoGravacao.Incerta(
                    "Não foi possível gravar a etiqueta (${motivo.detalhe.ifBlank { motivo.causa.name }}). " +
                        "Volte, leia a etiqueta de novo (deixe só ela perto do coletor) e cadastre pelo código que aparecer"
                )
            }
            aoTerminar(resultado)
        }
    }

    // Roda na thread do executor
    private fun gravarNoLeitor(r: RFIDReader, atual: String, novo: String): ResultadoGravacao {
        val acesso = r.Actions.TagAccess

        // PC atual: muda só o tamanho (bits 15–11) e preserva os outros (memória de usuário, XI, AFI/ISO)
        val pc = try {
            lerPalavras(acesso, atual, offset = 1, palavras = 1)?.toIntOrNull(16)
        } catch (e: Exception) {
            Log.w(TAG, "PC da etiqueta $atual não lido (${e.message}): usando o padrão de 96 bits")
            null
        }
        val pcNovo = CodigoEtiqueta.pcPara96Bits(pc)

        val escrita = acesso.WriteAccessParams().apply {
            memoryBank = MEMORY_BANK.MEMORY_BANK_EPC
            offset = 1                      // palavra 1 = PC; o EPC começa na palavra 2
            setWriteData(pcNovo + novo)
            writeDataLength = 7             // em palavras de 16 bits: PC + 6 do EPC
            accessPassword = 0
        }
        try {
            acesso.writeWait(atual, escrita, null, null)
        } catch (e: Exception) {
            // No Gen2 é comum a escrita acontecer e a resposta se perder: se a etiqueta já responde pelo código
            // novo, deu certo. Senão, devolve o erro (o chamador trata como incerto: a etiqueta pode ter mudado)
            if (conferir(acesso, novo)) {
                Log.w(TAG, "Escrita da etiqueta $atual devolveu erro (${e.message}), mas ela já responde com $novo: gravada")
                return ResultadoGravacao.Gravada(novo)
            }
            throw e
        }

        // Confere: a etiqueta tem que responder pelo código novo e devolver exatamente os 96 bits gravados
        if (!conferir(acesso, novo)) {
            Log.e(TAG, "Etiqueta $atual gravada com $novo, mas a conferência não confirmou")
            return ResultadoGravacao.Incerta("A gravação não pôde ser conferida. Volte, leia a etiqueta de novo e cadastre pelo código que aparecer")
        }
        Log.i(TAG, "Etiqueta $atual gravada com o código $novo (PC $pcNovo) e conferida")
        return ResultadoGravacao.Gravada(novo)
    }

    private fun conferir(acesso: com.zebra.rfid.api3.TagAccess, epc: String): Boolean =
        try {
            lerPalavras(acesso, epc, offset = 2, palavras = 6) == epc
        } catch (e: Exception) {
            false
        }

    private fun lerPalavras(acesso: com.zebra.rfid.api3.TagAccess, epc: String, offset: Int, palavras: Int): String? {
        val leitura = acesso.ReadAccessParams().apply {
            memoryBank = MEMORY_BANK.MEMORY_BANK_EPC
            this.offset = offset
            count = palavras
            accessPassword = 0
        }
        return acesso.readWait(epc, leitura, null)?.memoryBankData?.uppercase()
    }

    // ================= FALHAS E BATERIA =================

    /** Traduz uma exceção do SDK da Zebra: pelo código (RFIDResults), quando há, e pelas palavras do texto. */
    private fun motivoDe(e: Throwable): MotivoFalhaLeitor {
        val resultado = (e as? OperationFailureException)?.results
        // As "constantes" do SDK não são enum do Java: compara pelo código numérico (ver eventStatusNotify)
        val causaSdk = when (resultado?.ordinal) {
            null -> null
            RFIDResults.RFID_OPERATION_IN_PROGRESS.ordinal -> CausaFalhaLeitor.OPERACAO_EM_ANDAMENTO
            RFIDResults.RFID_COMM_NO_CONNECTION.ordinal -> CausaFalhaLeitor.SEM_COMUNICACAO
            RFIDResults.RFID_API_COMMAND_TIMEOUT.ordinal -> CausaFalhaLeitor.TEMPO_ESGOTADO
            RFIDResults.RFID_READER_REGION_NOT_CONFIGURED.ordinal -> CausaFalhaLeitor.REGIAO_NAO_CONFIGURADA
            else -> null
        }
        val textos = when (e) {
            is OperationFailureException -> listOf(e.vendorMessage, e.statusDescription, resultado?.toString(), e.message)
            is InvalidUsageException -> listOf(e.vendorMessage, e.info, e.message)
            else -> listOf(e.message, e.javaClass.simpleName)
        }
        return MotivoLeitor.classificar(causaSdk, textos)
    }

    private fun descricaoLog(motivo: MotivoFalhaLeitor): String =
        if (motivo.detalhe.isBlank()) motivo.causa.name else "${motivo.detalhe} (${motivo.causa.name})"

    private fun registrarAgrupado(agrupador: AgrupadorFalhas, mensagem: String, erro: Throwable?) {
        val omitidas = agrupador.liberar() ?: return
        RegistroErros.registrar(appContext, TipoErro.LEITOR_RFID, mensagem + AgrupadorFalhas.sufixo(omitidas), erro)
    }

    // Gatilho apertado e a leitura (ou a localização) não começou: antes só ia para o log, e o operador ficava sem
    // som nem mensagem. Agora: bip de erro, faixa com o motivo por alguns segundos e registro para o servidor
    private fun falhaAoIniciarLeitura(e: Exception, oQue: String) {
        val motivo = motivoDe(e)
        val doUso = !MotivoLeitor.ehDefeito(motivo.causa)
        if (doUso) Log.w(TAG, "Leitura não começou ($oQue): ${descricaoLog(motivo)}")
        else Log.e(TAG, "Erro ao iniciar $oQue: ${descricaoLog(motivo)}", e)
        atualizarDetalhe { it.copy(falhaLeitura = motivo, momentoFalhaLeitura = SystemClock.elapsedRealtime()) }
        avisoErro.erro()
        // Situação de uso (ex.: coletor no carregador) não é defeito: o operador vê na faixa, mas não vai para a tela de
        // erros do servidor (visto em 09/11: cada aperto no berço virava um erro "LEITOR_RFID")
        if (!doUso) registrarAgrupado(agrupadorFalhaLeitura, "Gatilho apertado e $oQue não começou: ${descricaoLog(motivo)}", e)
    }

    // A leitura começou: uma falha anterior ainda na faixa sai na hora
    private fun leituraIniciada() {
        atualizarDetalhe { if (it.falhaLeitura == null) it else it.copy(falhaLeitura = null) }
    }

    // A bateria do leitor veio do SDK (evento): a do aparelho deixa de ser usada
    @Volatile
    private var bateriaPeloSdk = false

    // Só acessado na thread do executor
    private var acompanhandoBateria = false

    private val receptorBateria = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (bateriaPeloSdk) return
            val nivel = BateriaLeitor.percentual(
                intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            )
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val carregando = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            atualizarBateria(nivel, carregando)
        }
    }

    // No MC3300x o leitor é alimentado pela bateria do coletor: acompanha a do aparelho enquanto o leitor está conectado
    private fun acompanharBateriaDoAparelho() {
        if (acompanhandoBateria) return
        try {
            // EXPORTED: BATTERY_CHANGED é protegido (só o sistema envia). Com NOT_EXPORTED, abaixo do Android 13 o
            // ContextCompat exige uma permissão própria do app e o Android recusa entregar o último estado (o "sticky")
            // no registro ("Permission Denial: broadcasting BATTERY_CHANGED from null", visto no MC3300x em 11/11):
            // a faixa do leitor ficava sem bateria até o nível mudar. O estado devolvido no registro vai direto também
            val atual = ContextCompat.registerReceiver(
                appContext, receptorBateria, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_EXPORTED
            )
            atual?.let { receptorBateria.onReceive(appContext, it) }
            acompanhandoBateria = true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Não foi possível acompanhar a bateria do aparelho", e)
        }
    }

    private fun pararDeAcompanharBateriaDoAparelho() {
        if (!acompanhandoBateria) return
        acompanhandoBateria = false
        try {
            appContext.unregisterReceiver(receptorBateria)
        } catch (e: RuntimeException) {
            Log.d(TAG, "Receptor de bateria já removido: ${e.message}")
        }
    }

    private fun atualizarBateria(nivel: Int?, carregando: Boolean) {
        val (anterior, atual) = synchronized(travaDetalhe) {
            val antes = BateriaLeitor.classificar(detalheAtual.bateria, detalheAtual.carregando)
            atualizarDetalhe { it.copy(bateria = nivel, carregando = carregando) }
            antes to BateriaLeitor.classificar(nivel, carregando)
        }
        if (atual == anterior) return
        when (atual) {
            AlertaBateria.CRITICA -> Log.w(TAG, "Bateria do leitor RFID crítica: $nivel%")
            AlertaBateria.BAIXA -> Log.w(TAG, "Bateria do leitor RFID baixa: $nivel%")
            AlertaBateria.NORMAL -> Log.i(TAG, "Bateria do leitor RFID sem alerta: ${nivel ?: "?"}%${if (carregando) " (carregando)" else ""}")
        }
        // Alerta mais forte: o bip de erro, uma vez ao entrar na faixa crítica, se alguém estiver lendo
        if (BateriaLeitor.deveTocarAlerta(anterior, atual) && ouvinte != null) avisoErro.erro()
    }

    // Compara com o último estado PUBLICADO (o value do LiveData só muda depois, na thread principal)
    @Volatile
    private var ultimoEstado = EstadoLeitor.PARADO

    private fun atualizarEstado(novo: EstadoLeitor) {
        if (ultimoEstado == novo) return
        ultimoEstado = novo
        _estado.postValue(novo)
    }

    // ================= QUEM RECEBE AS LEITURAS =================

    /**
     * A tela [quem] passa a receber as leituras (substitui a anterior) e o leitor é conectado,
     * se ainda não estiver. Chame no onResume.
     */
    fun definirOuvinte(quem: Any, ouvinte: OuvinteLeitura) {
        dono = quem
        this.ouvinte = ouvinte
        Log.d(TAG, "Leituras agora vão para ${quem.javaClass.simpleName}")

        executor.execute {
            cancelarDesconexaoOciosa()
            // Já há uma tentativa marcada: não antecipa (evita conectar duas vezes seguidas)
            if (proximaTentativa == null && this.ouvinte != null) tentarConectar()
        }
    }

    /** Para de entregar leituras para [quem], se ainda for ela a dona. Chame no onPause. */
    fun removerOuvinte(quem: Any) {
        if (dono !== quem) return
        dono = null
        ouvinte = null
        locatingEpc = null

        // Sem tela de leitura, não adianta ficar tentando reconectar; e, se nenhuma abrir logo, desconecta
        executor.execute {
            if (ouvinte != null) return@execute
            cancelarProximaTentativa()
            // As tentativas acabaram de ser canceladas: a faixa deixa de dizer "tentando de novo"
            if (ultimoEstado == EstadoLeitor.DESCONECTADO) atualizarEstado(EstadoLeitor.PARADO)
            agendarDesconexaoOciosa()
        }
    }

    // ================= DESCONEXÃO SEM TELA DE LEITURA =================

    // Só acessada na thread do executor
    private var desconexaoOciosa: ScheduledFuture<*>? = null

    private fun agendarDesconexaoOciosa() {
        if (desconexaoOciosa != null || reader == null) return
        desconexaoOciosa = executor.schedule({
            desconexaoOciosa = null
            if (ouvinte != null || reader == null) return@schedule
            Log.i(TAG, "Nenhuma tela de leitura aberta há ${ReconexaoLeitor.DESCONECTAR_OCIOSO_MS / 60_000} min: " +
                "leitor RFID desconectado para economizar bateria (reconecta ao abrir uma tela de leitura)")
            liberarSdk()
            atualizarDetalhe { it.copy(motivoDesconexao = null) }
            atualizarEstado(EstadoLeitor.PARADO)
        }, ReconexaoLeitor.DESCONECTAR_OCIOSO_MS, TimeUnit.MILLISECONDS)
    }

    private fun cancelarDesconexaoOciosa() {
        desconexaoOciosa?.cancel(false)
        desconexaoOciosa = null
    }

    // ================= MODO LOCALIZAÇÃO =================

    /**
     * Liga o modo localização para [epc], só se [quem] for a tela que está recebendo as leituras. Uma resposta
     * do servidor que chega depois de a tela de localização sair não pode prender o leitor numa etiqueta
     * enquanto a tela principal ou o inventário estão abertos (eles parariam de registrar leituras).
     * Devolve false se ignorou o pedido.
     */
    fun iniciarLocalizacao(quem: Any, epc: String): Boolean {
        if (dono !== quem) {
            Log.w(TAG, "Pedido de localização de $epc ignorado: ${quem.javaClass.simpleName} não está recebendo as leituras")
            return false
        }
        locatingEpc = epc
        Log.i(TAG, "Modo localização iniciado: $epc")
        return true
    }

    /**
     * Sai do modo localização. Chamado no onPause (thread principal): o filtro acaba na hora, e o Stop do SDK
     * (que pode demorar e não pode rodar junto com uma desconexão) vai para a thread do leitor.
     */
    fun pararLocalizacao() {
        if (locatingEpc != null) Log.i(TAG, "Modo localização encerrado")
        locatingEpc = null
        // Saiu da tela com o gatilho ainda apertado: não deixa a antena ligada
        if (!localizandoComGatilho) return
        executor.execute {
            // Outra localização pode ter começado antes de chegar a vez: aí não para a nova
            if (localizandoComGatilho && locatingEpc == null) pararLocalizacaoNoLeitor()
        }
    }

    private fun pararLocalizacaoNoLeitor() {
        localizandoComGatilho = false
        try {
            reader?.Actions?.TagLocationing?.Stop()
            Log.d(TAG, "Localização parada no leitor")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar a localização", e)
        }
    }

    // ================= EVENTOS DO SDK =================

    // Exceção num evento do SDK subiria para a thread dele: fecharia o app ou mataria o despacho de eventos (leituras
    // paradas sem aviso). Fica no log e na tela de erros do servidor, e o leitor segue
    override fun eventStatusNotify(e: RfidStatusEvents) {
        try {
            tratarStatus(e)
        } catch (ex: Exception) {
            Log.e(TAG, "Falha ao tratar um evento do leitor RFID", ex)
            registrarAgrupado(agrupadorFalhaEvento, "Falha ao tratar um evento do leitor RFID: ${ex.javaClass.simpleName}: ${ex.message}", ex)
        }
    }

    private fun tratarStatus(e: RfidStatusEvents) {

        // As "constantes" do SDK Zebra são classes comuns (não enum do Java) e não sobrescrevem
        // equals(Object): compara pelo código numérico (ordinal), que não depende do texto do
        // toString() nem de o SDK devolver exatamente a mesma instância.
        val tipo = e.StatusEventData.statusEventType

        when (tipo.ordinal) {
            STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT.ordinal -> tratarGatilho(e)

            STATUS_EVENT_TYPE.DISCONNECTION_EVENT.ordinal -> {
                Log.w(TAG, "Leitor RFID desconectou (bateria, encaixe ou Bluetooth): reconectando automaticamente")
                executor.execute {
                    // Começo da queda (se já não havia uma em andamento): a volta é registrada com a duração
                    if (inicioQueda == null) {
                        inicioQueda = SystemClock.elapsedRealtime()
                        tentativasDesdeQueda = 0
                    }
                    liberarSdk()
                    atualizarDetalhe { it.copy(motivoDesconexao = MotivoFalhaLeitor(CausaFalhaLeitor.QUEDA, "evento de desconexão do leitor")) }
                    atualizarEstado(estadoDesconectado())
                    agendarNovaTentativa(ReconexaoLeitor.ESPERA_APOS_DESCONEXAO_MS)
                }
            }

            STATUS_EVENT_TYPE.BATTERY_EVENT.ordinal -> {
                val bateria = e.StatusEventData.BatteryData
                bateriaPeloSdk = true
                Log.d(TAG, "Bateria do leitor (evento do SDK): ${bateria.level}%, carregando: ${bateria.charging}, causa: ${bateria.cause}")
                atualizarBateria(bateria.level, bateria.charging)
            }

            else -> Log.d(TAG, "Evento do leitor sem tratamento: $tipo")
        }
    }

    private fun tratarGatilho(e: RfidStatusEvents) {
        val evento = e.StatusEventData.HandheldTriggerEventData.handheldEvent
        // Gatilho emprestado ao leitor de código de barras (Configurações): não começa leitura RFID
        if (gatilhoNoCodigoDeBarras) return

        // Na thread do leitor (a mesma de conectar e desconectar), e não na de eventos do SDK: como no exemplo da Zebra,
        // o perform()/stop() não segura o despacho de eventos, apertar e soltar ficam sempre nessa ordem e não competem
        // com uma desconexão acontecendo ao mesmo tempo
        val acao: () -> Unit = when (evento.ordinal) {
            HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED.ordinal -> { _gatilhoApertado.postValue(true); ::iniciarInventario }
            HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED.ordinal -> { _gatilhoApertado.postValue(false); ::pararInventario }
            else -> {
                Log.w(TAG, "Evento de gatilho desconhecido: $evento (ordinal ${evento.ordinal})")
                return
            }
        }
        try {
            executor.execute {
                try {
                    acao()
                } catch (ex: Exception) {
                    Log.e(TAG, "Falha ao tratar o gatilho", ex)
                }
            }
        } catch (ex: RejectedExecutionException) {
            Log.w(TAG, "Gatilho ignorado: leitor sendo encerrado")
        }
    }

    private fun iniciarInventario() {
        // Ninguém na tela para receber: não adianta ligar a antena
        if (ouvinte == null) {
            Log.d(TAG, "Gatilho pressionado sem tela de leitura aberta: ignorado")
            return
        }

        // Localização: o inventário comum lê a etiqueta procurada uma vez só (depois de responder ela fica
        // "calada" para dar vez às outras) e a barra parava. O TagLocationing do SDK lê só ela, sem parar,
        // e já devolve a distância relativa
        val alvo = locatingEpc
        if (alvo != null) {
            try {
                reader?.Actions?.TagLocationing?.Perform(alvo, null, null)
                localizandoComGatilho = true
                leituraIniciada()
                Log.d(TAG, "Gatilho pressionado: localizando $alvo")
            } catch (e: Exception) {
                falhaAoIniciarLeitura(e, "a localização de $alvo")
            }
            return
        }

        try {
            reader?.Actions?.Inventory?.perform()
            leituraIniciada()
            Log.d(TAG, "Gatilho pressionado: leitura iniciada")
        } catch (e: Exception) {
            falhaAoIniciarLeitura(e, "a leitura")
        }
    }

    private fun pararInventario() {
        if (localizandoComGatilho) {
            pararLocalizacaoNoLeitor()
            return
        }

        try {
            reader?.Actions?.Inventory?.stop()
            Log.d(TAG, "Gatilho solto: leitura parada")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar a leitura", e)
        }
    }

    override fun eventReadNotify(e: RfidReadEvents) {
        try {
            tratarLeituras(e)
        } catch (ex: Exception) {
            Log.e(TAG, "Falha ao tratar as etiquetas lidas", ex)
            registrarAgrupado(agrupadorFalhaEvento, "Falha ao tratar as etiquetas lidas: ${ex.javaClass.simpleName}: ${ex.message}", ex)
        }
    }

    private fun tratarLeituras(e: RfidReadEvents) {

        // Inventário comum: as leituras ficam na fila do SDK (getReadTags). Localização (TagLocationing): a fila vem
        // vazia e a leitura, com a distância, vem anexada ao próprio evento (setAttachTagDataWithReadEvent)
        val tags = reader?.Actions?.getReadTags(100)
            ?: e.readEventData?.tagData?.let { arrayOf(it) }
            ?: return
        val destino = ouvinte ?: return
        val filtro = locatingEpc

        for (tag in tags) {
            // Leitura do TagLocationing: traz a distância da etiqueta procurada (o EPC pode vir vazio)
            if (tag.isContainsLocationInfo) {
                val alvo = filtro ?: continue
                destino.aoLocalizar(alvo, tag.LocationInfo.relativeDistance.toInt())
                continue
            }

            val epc = tag.tagID ?: continue
            if (filtro != null && epc != filtro) continue

            val rssi = tag.peakRSSI.toInt()
            // Alcance e "só da empresa" (Configurações) valem para o gatilho; o Localizar precisa dos sinais fracos
            if (filtro == null && !FiltroLeitura.aceita(epc, rssi, rssiMinimo, soEtiquetasEmpresa && destino.usaFiltroEmpresa)) continue

            destino.aoLer(epc, rssi)
        }
    }

    companion object {
        private const val TAG = "RfidManager"

        @Volatile
        private var instancia: RfidManager? = null

        fun get(context: Context): RfidManager =
            instancia ?: synchronized(this) {
                instancia ?: RfidManager(context.applicationContext).also { instancia = it }
            }
    }
}
