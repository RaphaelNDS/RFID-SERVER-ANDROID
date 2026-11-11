package com.example.rfid_server.leituras

import android.content.Intent
import com.example.rfid_server.telas.CliqueUnico
import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.rfid_server.leituras.MainViewModel
import com.example.rfid_server.R
import com.example.rfid_server.cadastro.CadastroTagActivity
import com.example.rfid_server.adapter.NaoCadastradaAdapter
import com.example.rfid_server.dados.ItemNaoCadastrada

// Só exibe: quem recebe a leitura e envia ao servidor é a MainActivity. Tocar abre o cadastro da etiqueta
class FragmentNaoCadastradas : Fragment(R.layout.frag_nao_cadastradas) {

    override fun onViewCreated(v: View, s: Bundle?) {

        val recycler = v.findViewById<RecyclerView>(R.id.recyclerNao)
        recycler.layoutManager = LinearLayoutManager(requireContext())

        val viewModel = ViewModelProvider(requireActivity()).get(MainViewModel::class.java)

        val adapter = NaoCadastradaAdapter { epc ->
            // Toque duplo com luva abria dois Cadastros empilhados
            if (!CliqueUnico.permitir()) return@NaoCadastradaAdapter
            // Cadastro em sequência: as ainda sem cadastro que vêm depois desta seguem junto (o Cadastro oferece a próxima)
            val lista = viewModel.naoCadastradas.value.orEmpty()
            val fila = lista.dropWhile { it.epc != epc }.drop(1)
                .filter { it.situacao == ItemNaoCadastrada.SEM_CADASTRO }.map { it.epc }
            startActivity(
                Intent(requireContext(), CadastroTagActivity::class.java)
                    .putExtra(CadastroTagActivity.EXTRA_EPC, epc)
                    .putStringArrayListExtra(CadastroTagActivity.EXTRA_FILA, ArrayList(fila))
            )
        }
        recycler.adapter = adapter

        val vazio = v.findViewById<View>(R.id.txtVazioNao)
        viewModel.naoCadastradas.observe(viewLifecycleOwner) {
            adapter.atualizar(it)
            vazio.isVisible = it.isEmpty()
        }
    }
}
