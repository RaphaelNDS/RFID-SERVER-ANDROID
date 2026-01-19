package com.example.rfid_server.activities

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.viewpager2.widget.ViewPager2
import com.example.rfid_server.R
import com.example.rfid_server.RfidManager
import com.example.rfid_server.service.TagService
import com.example.rfid_server.adapter.MainPagerAdapter
import com.example.rfid_server.fragment.FragmentLeitura
import com.example.rfid_server.fragment.FragmentNaoCadastradas
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

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        title = "RFID Server"

        pager = findViewById(R.id.pager)
        val tabs = findViewById<TabLayout>(R.id.tabs)

        pager.adapter = MainPagerAdapter(this)

        TabLayoutMediator(tabs, pager) { tab, pos ->
            tab.text = if (pos == 0) "Leitura" else "Não cadastradas"
        }.attach()

        tagService = TagService(this)

        rfidManager = RfidManager(this) { epc, _ ->
            runOnUiThread { processaTag(epc) }
        }

        if (!rfidManager.connect()) {
            Toast.makeText(this, "Erro ao conectar RFID", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {

            R.id.menu_localizar -> {
                startActivity(Intent(this, LocalizarActivity::class.java))
                true
            }

            R.id.menu_settings -> {
                abrirConfiguracoes()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }


    private fun abrirConfiguracoes() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun processaTag(epc: String) {
        if (lidas.contains(epc)) return
        lidas.add(epc)
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
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        rfidManager.disconnect()
    }
}
