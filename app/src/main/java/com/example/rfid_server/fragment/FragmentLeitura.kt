package com.example.rfid_server.fragment

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.MainViewModel
import com.example.rfid_server.R
import com.example.rfid_server.activities.FichaBem
import com.example.rfid_server.adapter.TagAdapter

// Só exibe: quem recebe a leitura e consulta o servidor é a MainActivity.
// Mostra também as lidas que esperam o servidor identificar (sem rede), em estilo discreto
class FragmentLeitura : Fragment(R.layout.frag_leitura) {

    override fun onViewCreated(v: View, s: Bundle?) {

        val recycler = v.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        val vazio = v.findViewById<View>(R.id.txtVazioLeitura)

        val adapter = TagAdapter { tag ->
            (activity as? androidx.appcompat.app.AppCompatActivity)?.let { FichaBem.mostrar(it, tag) }
        }
        recycler.adapter = adapter

        val viewModel = ViewModelProvider(requireActivity()).get(MainViewModel::class.java)
        viewModel.leituras.observe(viewLifecycleOwner) {
            adapter.atualizar(it)
            vazio.isVisible = it.isEmpty()
        }
    }
}
