package com.example.rfid_server

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.zebra.rfid.api3.ENUM_TRANSPORT
import com.zebra.rfid.api3.ENUM_TRIGGER_MODE
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE
import com.zebra.rfid.api3.RFIDReader
import com.zebra.rfid.api3.Readers
import com.zebra.rfid.api3.RfidEventsListener
import com.zebra.rfid.api3.RfidReadEvents
import com.zebra.rfid.api3.RfidStatusEvents
import com.zebra.rfid.api3.STATUS_EVENT_TYPE
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Situação da conexão com o leitor RFID, mostrada no topo das telas de leitura. */
enum class EstadoLeitor(val descricao: String) {
    CONECTANDO("Conectando ao leitor RFID..."),
    CONECTADO("Leitor RFID conectado"),
    DESCONECTADO("Leitor RFID desconectado: tentando de novo")
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
 *
 * Conectar e desconectar rodam numa thread própria: o SDK da Zebra pode demorar segundos, e na
 * thread principal isso travaria a tela (ANR). Tudo que mexe na conexão roda nessa thread.
 */
class RfidManager private constructor(private val appContext: Context) : RfidEventsListener {

    /** Recebe EPC e RSSI. Chamado na thread do SDK da Zebra (use runOnUiThread para mexer na tela). */
    fun interface OuvinteLeitura {
        fun aoLer(epc: String, rssi: Int)
    }

    private val executor = Executors.newSingleThreadScheduledExecutor()

    private var readers: Readers? = null
    private var reader: RFIDReader? = null

    // Só acessados na thread do executor
    private var tentativasSemSucesso = 0
    private var proximaTentativa: ScheduledFuture<*>? = null

    @Volatile
    private var dono: Any? = null

    @Volatile
    private var ouvinte: OuvinteLeitura? = null

    @Volatile
    private var locatingEpc: String? = null

    private val _estado = MutableLiveData(EstadoLeitor.DESCONECTADO)
    val estado: LiveData<EstadoLeitor> = _estado

    val conectado: Boolean
        get() = reader?.isConnected == true

    // ================= CONEXÃO =================

    /** Botão "Reconectar": tenta agora, sem esperar a próxima tentativa automática. */
    fun reconectarAgora() {
        executor.execute {
            Log.i(TAG, "Reconexão do leitor RFID pedida pelo operador")
            cancelarProximaTentativa()
            tentativasSemSucesso = 0
            tentarConectar()
        }
    }

    // Roda na thread do executor
    private fun tentarConectar() {
        proximaTentativa = null
        if (conectado) {
            atualizarEstado(EstadoLeitor.CONECTADO)
            return
        }

        atualizarEstado(EstadoLeitor.CONECTANDO)

        if (conectarAgora()) {
            if (tentativasSemSucesso > 0) Log.i(TAG, "Leitor RFID reconectado depois de $tentativasSemSucesso tentativa(s) sem sucesso")
            tentativasSemSucesso = 0
            atualizarEstado(EstadoLeitor.CONECTADO)
            return
        }

        tentativasSemSucesso++
        atualizarEstado(EstadoLeitor.DESCONECTADO)
        agendarNovaTentativa(ReconexaoLeitor.esperaMs(tentativasSemSucesso))
    }

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
                if (ReconexaoLeitor.deveRegistrar(tentativasSemSucesso + 1)) Log.e(TAG, "Nenhum leitor RFID encontrado")
                return false
            }

            val r = lista[0].rfidReader
            reader = r

            if (!r.isConnected) r.connect()

            configurarLeitor(r)
            Log.i(TAG, "Leitor RFID conectado: ${lista[0].name}")
            true

        } catch (e: Exception) {
            if (ReconexaoLeitor.deveRegistrar(tentativasSemSucesso + 1)) Log.e(TAG, "Erro ao conectar no leitor RFID", e)
            false
        }
    }

    private fun configurarLeitor(r: RFIDReader) {
        r.Events.addEventsListener(this)
        r.Events.setHandheldEvent(true)
        r.Events.setTagReadEvent(true)
        r.Events.setAttachTagDataWithReadEvent(true)
        r.Events.setReaderDisconnectEvent(true)

        try {
            val antConfig = r.Config.Antennas.getAntennaRfConfig(1)
            antConfig.transmitPowerIndex = r.ReaderCapabilities.transmitPowerLevelValues.size - 1
            r.Config.Antennas.setAntennaRfConfig(1, antConfig)

            // leitura SOMENTE no gatilho
            r.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar o leitor RFID", e)
        }
    }

    /** Desconecta em segundo plano. Chame só quando o app estiver saindo de vez. */
    fun desconectar() {
        executor.execute {
            cancelarProximaTentativa()
            tentativasSemSucesso = 0
            liberarSdk()
            atualizarEstado(EstadoLeitor.DESCONECTADO)
            Log.i(TAG, "Leitor RFID desconectado")
        }
    }

    private fun liberarSdk() {
        try {
            reader?.Events?.removeEventsListener(this)
            if (reader?.isConnected == true) reader?.disconnect()
            readers?.Dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desconectar do leitor RFID", e)
        } finally {
            reader = null
            readers = null
        }
    }

    // Compara com o último estado PUBLICADO (o value do LiveData só muda depois, na thread principal)
    @Volatile
    private var ultimoEstado = EstadoLeitor.DESCONECTADO

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

        // Sem tela de leitura, não adianta ficar tentando reconectar
        executor.execute { if (ouvinte == null) cancelarProximaTentativa() }
    }

    // ================= MODO LOCALIZAÇÃO =================

    fun iniciarLocalizacao(epc: String) {
        locatingEpc = epc
        Log.i(TAG, "Modo localização iniciado: $epc")
    }

    fun pararLocalizacao() {
        if (locatingEpc != null) Log.i(TAG, "Modo localização encerrado")
        locatingEpc = null
    }

    // ================= EVENTOS DO SDK =================

    override fun eventStatusNotify(e: RfidStatusEvents) {

        // As "constantes" do SDK Zebra são classes comuns (não enum do Java) e não sobrescrevem
        // equals(Object): compara pelo código numérico (ordinal), que não depende do texto do
        // toString() nem de o SDK devolver exatamente a mesma instância.
        val tipo = e.StatusEventData.statusEventType

        when (tipo.ordinal) {
            STATUS_EVENT_TYPE.HANDHELD_TRIGGER_EVENT.ordinal -> tratarGatilho(e)

            STATUS_EVENT_TYPE.DISCONNECTION_EVENT.ordinal -> {
                Log.w(TAG, "Leitor RFID desconectou (bateria, encaixe ou Bluetooth): reconectando automaticamente")
                executor.execute {
                    liberarSdk()
                    atualizarEstado(EstadoLeitor.DESCONECTADO)
                    agendarNovaTentativa(ReconexaoLeitor.ESPERA_APOS_DESCONEXAO_MS)
                }
            }

            else -> Log.d(TAG, "Evento do leitor sem tratamento: $tipo")
        }
    }

    private fun tratarGatilho(e: RfidStatusEvents) {
        val evento = e.StatusEventData.HandheldTriggerEventData.handheldEvent

        when (evento.ordinal) {
            HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_PRESSED.ordinal -> iniciarInventario()
            HANDHELD_TRIGGER_EVENT_TYPE.HANDHELD_TRIGGER_RELEASED.ordinal -> pararInventario()
            else -> Log.w(TAG, "Evento de gatilho desconhecido: $evento (ordinal ${evento.ordinal})")
        }
    }

    private fun iniciarInventario() {
        // Ninguém na tela para receber: não adianta ligar a antena
        if (ouvinte == null) {
            Log.d(TAG, "Gatilho pressionado sem tela de leitura aberta: ignorado")
            return
        }

        try {
            reader?.Actions?.Inventory?.perform()
            Log.d(TAG, "Gatilho pressionado: leitura iniciada")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar a leitura", e)
        }
    }

    private fun pararInventario() {
        try {
            reader?.Actions?.Inventory?.stop()
            Log.d(TAG, "Gatilho solto: leitura parada")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar a leitura", e)
        }
    }

    override fun eventReadNotify(e: RfidReadEvents) {

        val tags = reader?.Actions?.getReadTags(100) ?: return
        val destino = ouvinte ?: return
        val filtro = locatingEpc

        for (tag in tags) {
            val epc = tag.tagID ?: continue
            if (filtro != null && epc != filtro) continue

            destino.aoLer(epc, tag.peakRSSI.toInt())
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
