package com.example.rfid_server.activities

import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.rfid_server.AppConfig
import com.example.rfid_server.R
import com.example.rfid_server.service.TagService
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

class SettingsActivity : AppCompatActivity() {

    private lateinit var edtIp: EditText
    private lateinit var btnSalvar: Button
    private lateinit var btnTestar: Button
    private lateinit var txtStatus: TextView

    private lateinit var tagService: TagService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        edtIp = findViewById(R.id.edtIp)
        btnSalvar = findViewById(R.id.btnSalvar)
        btnTestar = findViewById(R.id.btnTestarServidor)
        txtStatus = findViewById(R.id.txtStatusServidor)

        tagService = TagService(this)

        edtIp.setText(AppConfig.getServerIp(this))

        testarServidor()

        btnTestar.setOnClickListener {
            testarServidor()
        }

        btnSalvar.setOnClickListener {
            val ip = edtIp.text.toString().trim()

            if (ip.isEmpty()) {
                Toast.makeText(this, "Informe o IP do servidor", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            AppConfig.saveServerIp(this, ip)
            Toast.makeText(this, "Configurações salvas!", Toast.LENGTH_SHORT).show()

            testarServidor()
        }
    }

    private fun testarServidor() {
        txtStatus.text = "Servidor: testando..."
        txtStatus.setTextColor(Color.GRAY)

        tagService.testarConexao { conectado ->
            runOnUiThread {
                if (conectado) {
                    txtStatus.text = "Servidor: conectado ✅"
                    txtStatus.setTextColor(Color.parseColor("#2E7D32"))
                } else {
                    txtStatus.text = "Servidor: sem conexão ❌"
                    txtStatus.setTextColor(Color.parseColor("#C62828"))
                }
            }
        }
    }
}
