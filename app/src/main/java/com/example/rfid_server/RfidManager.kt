package com.example.rfid_server

import android.content.Context
import android.util.Log
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

/**
 * Gerenciador ÚNICO do leitor RFID Zebra para o app inteiro.
 *
 * Antes, cada tela criava a própria conexão: a tela principal continuava conectada (e registrando
 * leituras) enquanto a de localização abria outra. Agora há uma conexão só, e as leituras vão
 * apenas para a tela visível: ela se registra no onResume ([definirOuvinte]) e sai no onPause
 * ([removerOuvinte]).
 *
 * Conectar e desconectar rodam numa thread própria: o SDK da Zebra pode demorar segundos, e na
 * thread principal isso travaria a tela (ANR).
 */
class RfidManager private constructor(private val appContext: Context) : RfidEventsListener {

    /** Recebe EPC e RSSI. Chamado na thread do SDK da Zebra (use runOnUiThread para mexer na tela). */
    fun interface OuvinteLeitura {
        fun aoLer(epc: String, rssi: Int)
    }

    private val executor = Executors.newSingleThreadExecutor()

    private var readers: Readers? = null
    private var reader: RFIDReader? = null

    @Volatile
    private var dono: Any? = null

    @Volatile
    private var ouvinte: OuvinteLeitura? = null

    @Volatile
    private var locatingEpc: String? = null

    val conectado: Boolean
        get() = reader?.isConnected == true

    // ================= CONEXÃO =================

    /**
     * Conecta em segundo plano, se ainda não estiver conectado.
     * [aoTerminar] recebe true/false e roda na thread do leitor.
     */
    fun conectar(aoTerminar: ((Boolean) -> Unit)? = null) {
        executor.execute {
            val ok = conectarAgora()
            aoTerminar?.invoke(ok)
        }
    }

    private fun conectarAgora(): Boolean {
        if (conectado) return true

        return try {
            Log.i(TAG, "Conectando ao leitor RFID...")

            // Uma tentativa anterior pode ter deixado o SDK pela metade
            liberarSdk()

            readers = Readers(appContext, ENUM_TRANSPORT.ALL)
            val lista = readers?.GetAvailableRFIDReaderList()

            if (lista.isNullOrEmpty()) {
                Log.e(TAG, "Nenhum leitor RFID encontrado")
                return false
            }

            val r = lista[0].rfidReader
            reader = r

            if (!r.isConnected) r.connect()

            configurarLeitor(r)
            Log.i(TAG, "Leitor RFID conectado: ${lista[0].name}")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao conectar no leitor RFID", e)
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
            liberarSdk()
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

    // ================= QUEM RECEBE AS LEITURAS =================

    /** A tela [quem] passa a receber as leituras (substitui a anterior). Chame no onResume. */
    fun definirOuvinte(quem: Any, ouvinte: OuvinteLeitura) {
        dono = quem
        this.ouvinte = ouvinte
        Log.d(TAG, "Leituras agora vão para ${quem.javaClass.simpleName}")
    }

    /** Para de entregar leituras para [quem], se ainda for ela a dona. Chame no onPause. */
    fun removerOuvinte(quem: Any) {
        if (dono !== quem) return
        dono = null
        ouvinte = null
        locatingEpc = null
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
                Log.w(TAG, "Leitor RFID desconectou (bateria, encaixe ou Bluetooth). Reconectando na próxima vez que uma tela abrir")
                executor.execute { liberarSdk() }
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
