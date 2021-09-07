package com.chopyourbrain.kontrol

import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.os.Bundle
import android.view.Menu
import androidx.appcompat.app.AppCompatActivity
import com.chopyourbrain.kontrol.ktor.NetCall
import com.chopyourbrain.kontrol.network.NetworkFragment
import com.chopyourbrain.kontrol.network.detail.NetworkDetailFragment
import com.chopyourbrain.kontrol.properties.PropertiesFragment
import com.google.android.material.bottomnavigation.BottomNavigationView

internal class DebugMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        supportActionBar?.title = "Debug menu"

        val bottomNav = findViewById<BottomNavigationView>(R.id.bottom_nav)
        bottomNav.menu.add(Menu.NONE, 1, Menu.NONE, "Properties").setIcon(R.drawable.ic_properties).itemId
        bottomNav.menu.add(Menu.NONE, 2, Menu.NONE, "Network").setIcon(R.drawable.ic_network)
        bottomNav.setOnItemSelectedListener {
            val currentFragment = when (it.itemId) {
                1 -> PropertiesFragment.create()
                2 -> NetworkFragment.create()
                else -> null
            }
            currentFragment?.let { fragment ->
                supportFragmentManager.beginTransaction().replace(R.id.container, fragment).commit()
            }
            return@setOnItemSelectedListener true
        }
        if (supportFragmentManager.fragments.isEmpty())
            supportFragmentManager.beginTransaction().replace(R.id.container, PropertiesFragment.create()).commit()
    }

    fun goToNetworkDetail(netCall: NetCall) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.container, NetworkDetailFragment.create(netCall.id))
            .addToBackStack(null)
            .commit()
    }

    companion object {
        fun startActivity(context: Context) {
            context.startActivity(Intent(context, DebugMenuActivity::class.java).apply {
                flags = FLAG_ACTIVITY_NEW_TASK
            })
        }
    }
}