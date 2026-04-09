package com.terenac.opencourt

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "opencourt_prefs"

    // Vraća SharedPreferences instancu za "opencourt_prefs" fajl u privatnom režimu
    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // Proverava da li je korisnik sa datim UID-om već video onboarding ekran
    fun isOnboardingSeen(ctx: Context, uid: String): Boolean =
        prefs(ctx).getBoolean("onboarding_seen_$uid", false)

    // Označava da je korisnik sa datim UID-om video onboarding ekran i čuva u SharedPreferences
    fun setOnboardingSeen(ctx: Context, uid: String) {
        prefs(ctx).edit().putBoolean("onboarding_seen_$uid", true).apply()
    }
}
