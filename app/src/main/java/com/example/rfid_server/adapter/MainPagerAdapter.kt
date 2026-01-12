package com.example.rfid_server.adapter

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.rfid_server.activities.FragmentLeitura
import com.example.rfid_server.activities.FragmentNaoCadastradas

class MainPagerAdapter(activity: FragmentActivity) :
    FragmentStateAdapter(activity) {

    override fun getItemCount() = 2

    override fun createFragment(position: Int): Fragment =
        when (position) {
            0 -> FragmentLeitura()
            else -> FragmentNaoCadastradas()
        }
}
