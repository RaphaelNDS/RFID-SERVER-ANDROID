package com.example.rfid_server.activities

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.rfid_server.R
import com.example.rfid_server.RfidManager
import com.example.rfid_server.TagService
import com.example.rfid_server.adapter.MainPagerAdapter
import com.example.rfid_server.adapter.TagAdapter
import com.example.rfid_server.adapter.TagNaoAdapter
import com.example.rfid_server.view.TagView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator


class MainActivity : AppCompatActivity() {

    private lateinit var rfidManager: RfidManager
    private lateinit var tagService: TagService

    private val lidas = mutableSetOf<String>()

    private lateinit var pager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pager = findViewById(R.id.pager)
        val tabs = findViewById<TabLayout>(R.id.tabs)

        pager.adapter = MainPagerAdapter(this)

        TabLayoutMediator(tabs, pager) { tab, pos ->
            tab.text = if (pos == 0) "Leitura" else "Não cadastradas"
        }.attach()

        tagService = TagService()

        rfidManager = RfidManager(this) { epc ->
            runOnUiThread {
                processaTag(epc)
            }
        }

        if (!rfidManager.connect()) {
            Toast.makeText(this, "Erro ao conectar RFID", Toast.LENGTH_LONG).show()
        }
    }

    private fun processaTag(epc: String) {

        if (lidas.contains(epc)) return
        lidas.add(epc)

        Log.d("MAIN", "Recebi EPC: $epc")

        consultaServidor(epc)
    }

    private fun consultaServidor(epc: String) {

        tagService.buscarTag(epc) { tag ->

            runOnUiThread {

                val fragLeitura =
                    supportFragmentManager.findFragmentByTag("f0")
                            as? FragmentLeitura

                val fragNao =
                    supportFragmentManager.findFragmentByTag("f1")
                            as? FragmentNaoCadastradas

                if (tag != null) {
                    fragLeitura?.adicionarTag(tag)
                } else {
                    fragNao?.adicionarTag(epc)
                    tagService.enviarNaoCadastrada(epc)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rfidManager.disconnect()
    }
}
