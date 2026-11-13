package com.alya.gamespace

import android.content.Context

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("gamespace", Context.MODE_PRIVATE)
    fun games(c: Context): Set<String> = p(c).getStringSet("games", emptySet())!!.toSet()
    fun setGames(c: Context, s: Set<String>) = p(c).edit().putStringSet("games", s).apply()
    fun long(c: Context, k: String, d: Long) = p(c).getLong(k, d)
    fun setLong(c: Context, k: String, v: Long) = p(c).edit().putLong(k, v).apply()
    fun int(c: Context, k: String, d: Int) = p(c).getInt(k, d)
    fun setInt(c: Context, k: String, v: Int) = p(c).edit().putInt(k, v).apply()
    fun bool(c: Context, k: String, d: Boolean) = p(c).getBoolean(k, d)
    fun setBool(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).apply()
    fun str(c: Context, k: String, d: String = ""): String = p(c).getString(k, d) ?: d
    fun setStr(c: Context, k: String, v: String) = p(c).edit().putString(k, v).apply()
    fun autoMode(c: Context) = int(c, "auto", 2)
}
