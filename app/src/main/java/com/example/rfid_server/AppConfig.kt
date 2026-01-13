package com.example.rfid_server

import android.content.Context

object AppConfig {

    private const val PREFS = "rfid_prefs"
    private const val KEY_SERVER_IP = "server_ip"
    private const val DEFAULT_IP = "182.17.10.130"

    fun saveServerIp(context: Context, ip: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SERVER_IP, ip)
            .apply()
    }

    fun getServerIp(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_IP, DEFAULT_IP) ?: DEFAULT_IP
    }

    fun getBaseUrl(context: Context): String {
        return "http://${getServerIp(context)}:8082"
    }
}

