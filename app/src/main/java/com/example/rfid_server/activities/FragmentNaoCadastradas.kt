package com.example.rfid_server.activities

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.R
import com.example.rfid_server.RfidManager
import com.example.rfid_server.TagService
import com.example.rfid_server.adapter.TagNaoAdapter

class FragmentNaoCadastradas : Fragment(R.layout.frag_nao_cadastradas) {

    private lateinit var rfidManager: RfidManager
    private lateinit var tagService: TagService

    private val lidas = mutableSetOf<String>()
    private val lista = mutableListOf<String>()
    private lateinit var adapter: TagNaoAdapter

    override fun onViewCreated(v: View, s: Bundle?) {

        tagService = TagService(requireContext())

        val recycler = v.findViewById<RecyclerView>(R.id.recyclerNao)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        adapter = TagNaoAdapter(lista)
        recycler.adapter = adapter

        rfidManager = RfidManager(requireContext()) { epc ->
            activity?.runOnUiThread {

                if (lidas.contains(epc)) return@runOnUiThread
                lidas.add(epc)

                adapter.add(epc)
                tagService.enviarNaoCadastrada(epc)
            }
        }

        rfidManager.connect()
    }

    fun adicionarTag(epc: String) {
        adapter.add(epc)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rfidManager.disconnect()
    }
}
