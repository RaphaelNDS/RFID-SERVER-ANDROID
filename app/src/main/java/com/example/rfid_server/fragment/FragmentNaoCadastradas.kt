package com.example.rfid_server.fragment

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.MainViewModel
import com.example.rfid_server.R
import com.example.rfid_server.activities.CadastroTagActivity
import com.example.rfid_server.adapter.NaoCadastradaAdapter

// Só exibe: quem recebe a leitura e envia ao servidor é a MainActivity. Tocar abre o cadastro da etiqueta
class FragmentNaoCadastradas : Fragment(R.layout.frag_nao_cadastradas) {

    override fun onViewCreated(v: View, s: Bundle?) {

        val recycler = v.findViewById<RecyclerView>(R.id.recyclerNao)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        val adapter = NaoCadastradaAdapter { epc ->
            startActivity(
                Intent(requireContext(), CadastroTagActivity::class.java)
                    .putExtra(CadastroTagActivity.EXTRA_EPC, epc)
            )
        }
        recycler.adapter = adapter

        val viewModel = ViewModelProvider(requireActivity()).get(MainViewModel::class.java)
        viewModel.naoCadastradas.observe(viewLifecycleOwner) { adapter.atualizar(it) }
    }
}
