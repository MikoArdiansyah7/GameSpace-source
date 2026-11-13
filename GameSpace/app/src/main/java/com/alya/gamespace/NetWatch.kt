package com.alya.gamespace

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Memantau jaringan lewat callback. Berbeda dengan activeNetwork (bisa null saat akses jaringan app dibatasi di latar,
 * misalnya penghemat data atau pembatasan baterai OEM), callback tetap melaporkan Wi-Fi / data yang aktif.
 */
object NetWatch {
    private val nets = HashMap<Network, NetLogic.Net>()
    private var defNet: Network? = null
    @Volatile private var cur = NetLogic.NONE
    private var cm: ConnectivityManager? = null
    private var cbAll: ConnectivityManager.NetworkCallback? = null
    private var cbDef: ConnectivityManager.NetworkCallback? = null

    /** Konstanta NetLogic (NONE/WIFI/CELL/LAN/VPN). */
    fun label(): Int = cur

    private fun conv(c: NetworkCapabilities) = NetLogic.Net(
        c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI), c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET), c.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET), c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))

    @Synchronized private fun recompute() {
        cur = NetLogic.label(ArrayList(nets.values), defNet?.let { nets[it] })
    }

    @Synchronized fun start(ctx: Context) {
        if (cm != null) return
        val m = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val all = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) { synchronized(NetWatch) { nets[n] = conv(c) }; recompute() }
            override fun onLost(n: Network) { synchronized(NetWatch) { nets.remove(n); if (defNet == n) defNet = null }; recompute() }
        }
        val def = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) { synchronized(NetWatch) { defNet = n }; recompute() }
            override fun onLost(n: Network) { synchronized(NetWatch) { if (defNet == n) defNet = null }; recompute() }
        }
        try {
            m.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), all)
            cbAll = all
        } catch (_: Throwable) {}
        try { m.registerDefaultNetworkCallback(def); cbDef = def } catch (_: Throwable) {}
        cm = m
    }

    @Synchronized fun stop() {
        val m = cm ?: return
        try { cbAll?.let { m.unregisterNetworkCallback(it) } } catch (_: Throwable) {}
        try { cbDef?.let { m.unregisterNetworkCallback(it) } } catch (_: Throwable) {}
        cbAll = null; cbDef = null; cm = null
        nets.clear(); defNet = null; cur = NetLogic.NONE
    }
}
