package com.example.rfid_server.activities

import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.compose.material3.Button
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.R
import com.example.rfid_server.RfidManager
import com.example.rfid_server.TagService
import com.example.rfid_server.adapter.TagAdapter
import com.example.rfid_server.view.TagView
import java.util.logging.Handler

class FragmentLeitura : Fragment(R.layout.frag_leitura) {

    private lateinit var tagService: TagService

    private val cadastradas = mutableListOf<TagView>()
    private val filaOffline = mutableListOf<String>()

    private lateinit var adapter: TagAdapter
    private var servidorOnline = false

    override fun onViewCreated(v: View, s: Bundle?) {

        val recycler = v.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = TagAdapter(cadastradas)
        recycler.adapter = adapter

        tagService = TagService(requireContext())
        testarServidor()
    }

    private fun testarServidor() {
        tagService.testarConexao { conectado ->
            activity?.runOnUiThread {
                servidorOnline = conectado
                if (conectado) sincronizarOffline()
            }
        }
    }

    fun receberEpc(epc: String) {
        if (servidorOnline) {
            consultar(epc)
        } else {
            modoOffline(epc)
        }
    }

    private fun consultar(epc: String) {
        tagService.buscarTag(epc) { tag ->
            activity?.runOnUiThread {
                if (tag != null) adapter.add(tag)
            }
        }
    }

    private fun modoOffline(epc: String) {
        filaOffline.add(epc)
        Toast.makeText(requireContext(),
            "Modo offline: EPC salvo",
            Toast.LENGTH_SHORT).show()
    }

    private fun sincronizarOffline() {
        if (filaOffline.isEmpty()) return
        val copia = filaOffline.toList()
        filaOffline.clear()
        copia.forEach { consultar(it) }
    }

    fun adicionarTag(tag: TagView) {
        adapter.add(tag)
    }
}

