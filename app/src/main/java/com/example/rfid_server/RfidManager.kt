package com.example.rfid_server

import android.content.Context
import android.util.Log
import com.zebra.rfid.api3.ENUM_TRANSPORT
import com.zebra.rfid.api3.ENUM_TRIGGER_MODE
import com.zebra.rfid.api3.HANDHELD_TRIGGER_EVENT_TYPE
import com.zebra.rfid.api3.RFIDReader
import com.zebra.rfid.api3.ReaderDevice
import com.zebra.rfid.api3.Readers
import com.zebra.rfid.api3.RfidEventsListener
import com.zebra.rfid.api3.RfidReadEvents
import com.zebra.rfid.api3.RfidStatusEvents
import com.zebra.rfid.api3.STATUS_EVENT_TYPE

class RfidManager(
    private val context: Context,
    private val onTagRead: (String, Int) -> Unit // epc + rssi
) : RfidEventsListener {

    private val TAG = "RfidManager"

    private var readers: Readers? = null
    private var readerDevice: ReaderDevice? = null
    private var reader: RFIDReader? = null

    private var locatingEpc: String? = null

    // ================= CONEXÃO =================

    fun connect(): Boolean {
        return try {
            readers = Readers(context, ENUM_TRANSPORT.ALL)
            val list = readers?.GetAvailableRFIDReaderList()

            if (list.isNullOrEmpty()) {
                Log.e(TAG, "Nenhum leitor RFID encontrado")
                return false
            }

            readerDevice = list[0]
            reader = readerDevice!!.rfidReader

            if (!reader!!.isConnected) {
                reader!!.connect()
            }

            configureReader()
            Log.d(TAG, "RFID conectado")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao conectar RFID", e)
            false
        }
    }

    // ================= CONFIGURAÇÃO =================

    private fun configureReader() {
        val r = reader ?: return

        r.Events.addEventsListener(this)
        r.Events.setHandheldEvent(true)
        r.Events.setTagReadEvent(true)
        r.Events.setAttachTagDataWithReadEvent(true)

        try {
            val antConfig = r.Config.Antennas.getAntennaRfConfig(1)
            antConfig.transmitPowerIndex =
                r.ReaderCapabilities.transmitPowerLevelValues.size - 1
            r.Config.Antennas.setAntennaRfConfig(1, antConfig)

            // leitura SOMENTE no gatilho
            r.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao configurar leitor", e)
        }
    }

    // ================= EVENTOS DE STATUS =================

    override fun eventStatusNotify(e: RfidStatusEvents) {

        val status = e.StatusEventData.statusEventType

        if (status.toString() == "HANDHELD_TRIGGER_EVENT") {

            val triggerEvent =
                e.StatusEventData.HandheldTriggerEventData.handheldEvent

            val pressed =
                triggerEvent.toString() == "HANDHELD_TRIGGER_PRESSED"

            if (pressed) {
                Log.d(TAG, "🔘 Gatilho pressionado")
                startInventory()
            } else {
                Log.d(TAG, "🔘 Gatilho solto")
                stopInventory()
            }
        }
    }


    // ================= INVENTORY =================

    private fun startInventory() {
        try {
            reader?.Actions?.Inventory?.perform()
            Log.d(TAG, "Inventory iniciado (gatilho)")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar inventory", e)
        }
    }

    private fun stopInventory() {
        try {
            reader?.Actions?.Inventory?.stop()
            Log.d(TAG, "Inventory parado (gatilho)")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar inventory", e)
        }
    }

    // ================= LEITURA =================

    override fun eventReadNotify(e: RfidReadEvents) {

        val tags = reader?.Actions?.getReadTags(100) ?: return

        for (tag in tags) {
            val epc = tag.tagID ?: continue
            val rssi = tag.peakRSSI.toInt()

            if (locatingEpc != null && epc != locatingEpc) continue

            onTagRead(epc, rssi)
        }
    }

    // ================= MODO LOCALIZAÇÃO =================

    fun startLocating(epc: String) {
        locatingEpc = epc
        Log.d(TAG, "Modo localização iniciado: $epc")
    }

    fun stopLocating() {
        locatingEpc = null
    }

    // ================= FINALIZAR =================

    fun disconnect() {
        try {
            reader?.Events?.removeEventsListener(this)
            if (reader?.isConnected == true) reader?.disconnect()
            readers?.Dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desconectar RFID", e)
        }
    }
}







/*
class RfidManager(
    private val context: Context,
    private val listener: (String) -> Unit
) : RfidEventsListener {

    private val TAG = "RfidManager"

    private var readers: Readers? = null
    private var readerDevice: ReaderDevice? = null
    private var reader: RFIDReader? = null

    fun connect(): Boolean {
        return try {
            readers = Readers(context, ENUM_TRANSPORT.ALL)
            val list = readers?.GetAvailableRFIDReaderList()

            if (list.isNullOrEmpty()) {
                Log.e(TAG, "Nenhum leitor RFID encontrado")
                return false
            }

            readerDevice = list[0]
            reader = readerDevice!!.rfidReader

            if (!reader!!.isConnected) {
                Log.d(TAG, "Conectando no RFID...")
                reader!!.connect()
            }

            configureReader()
            startInventory()

            Log.d(TAG, "RFID conectado e lendo")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Erro ao conectar RFID", e)
            false
        }
    }
        private fun configureReader() {
            val r = reader ?: return
            r.Events.addEventsListener(this)
            r.Events.setHandheldEvent(true)
            r.Events.setTagReadEvent(true)
            r.Events.setAttachTagDataWithReadEvent(false)

            try {
                val antConfig = r.Config.Antennas.getAntennaRfConfig(1)

                antConfig.transmitPowerIndex =
                    r.ReaderCapabilities.transmitPowerLevelValues.size - 1

                r.Config.Antennas.setAntennaRfConfig(1, antConfig)

                r.Config.setTriggerMode(ENUM_TRIGGER_MODE.RFID_MODE, true)

                Log.d(TAG, "Leitor configurado com sucesso")

            } catch (e: Exception) {
                Log.e(TAG, "Erro ao configurar leitor", e)
            }
        }

    fun startInventory() {
        try {
            reader?.Actions?.Inventory?.perform()
            Log.d(TAG, "Inventory iniciado")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao iniciar inventory", e)
        }
    }

    fun stopInventory() {
        try {
            reader?.Actions?.Inventory?.stop()
            Log.d(TAG, "Inventory parado")
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao parar inventory", e)
        }
    }

    override fun eventReadNotify(e: RfidReadEvents) {
        val tags = reader?.Actions?.getReadTags(100)

        if (tags != null) {
            for (tag in tags) {
                val epc = tag.tagID ?: continue
                Log.d(TAG, "TAG LIDA: $epc")
                listener.invoke(epc)
            }
        }
    }

    override fun eventStatusNotify(e: RfidStatusEvents) {
        Log.d(TAG, "Status: ${e.StatusEventData.statusEventType}")
    }

    fun disconnect() {
        try {
            reader?.Events?.removeEventsListener(this)
            if (reader?.isConnected == true) {
                reader?.disconnect()
            }
            readers?.Dispose()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao desconectar RFID", e)
        }
    }
}

*/