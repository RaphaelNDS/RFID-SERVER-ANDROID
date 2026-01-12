package com.example.rfid_server.activities

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.R
import com.example.rfid_server.RfidManager
import com.example.rfid_server.TagService
import com.example.rfid_server.adapter.TagAdapter
import com.example.rfid_server.view.TagView

class FragmentLeitura : Fragment(R.layout.frag_leitura) {

    private lateinit var rfidManager: RfidManager
    private lateinit var tagService: TagService

    private val lidas = mutableSetOf<String>()
    private val cadastradas = mutableListOf<TagView>()
    private lateinit var adapter: TagAdapter

    override fun onViewCreated(v: View, s: Bundle?) {

        val recycler = v.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = TagAdapter(cadastradas)
        recycler.adapter = adapter

        tagService = TagService()

        rfidManager = RfidManager(requireContext()) { epc ->
            activity?.runOnUiThread {
                if (lidas.contains(epc)) return@runOnUiThread
                lidas.add(epc)
                consultar(epc)
            }
        }

        rfidManager.connect()
    }

    private fun consultar(epc: String) {
        tagService.buscarTag(epc) { tag ->
            activity?.runOnUiThread {
                if (tag != null) adapter.add(tag)
            }
        }
    }

    fun adicionarTag(tag: TagView) {
        adapter.add(tag)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rfidManager.disconnect()
    }
}
