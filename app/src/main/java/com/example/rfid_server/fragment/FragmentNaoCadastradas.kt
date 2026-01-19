package com.example.rfid_server.fragment

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.R
import com.example.rfid_server.service.TagService
import com.example.rfid_server.adapter.TagNaoAdapter

class FragmentNaoCadastradas : Fragment(R.layout.frag_nao_cadastradas) {

    private lateinit var tagService: TagService
    private val lista = mutableListOf<String>()
    private lateinit var adapter: TagNaoAdapter

    override fun onViewCreated(v: View, s: Bundle?) {

        tagService = TagService(requireContext())

        val recycler = v.findViewById<RecyclerView>(R.id.recyclerNao)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        adapter = TagNaoAdapter(lista)
        recycler.adapter = adapter
    }

    fun adicionarTag(epc: String) {
        adapter.add(epc)
        tagService.enviarNaoCadastrada(epc)
    }
}