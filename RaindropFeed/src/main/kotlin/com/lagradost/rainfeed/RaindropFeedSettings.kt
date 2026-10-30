package com.lagradost.rainfeed

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

const val FEED_TOKEN_KEY = "feed_raindrop_token"
// v2 key names, so older saved lists don't override the defaults below.
// Bumped to v4 so the saved v3 list (which still had PornMommysCaps) doesn't override the new default.
const val FEED_PRIORITY_KEY = "feed_priority_v4"
const val FEED_ACCOUNTS_KEY = "feed_accounts_v2"
private const val FEED_PREFS = "raindrop_feed_settings"

// Keep both lists in sync with tools/xsync/accounts.json.
const val DEFAULT_PRIORITY =
    "PornMommyPlus,throatdemonz,ZBPGoon,btvvids11,gothbaddiez,sloppygoonettes,throatmoods," +
    "PornMommyPlusS,MommysBestBJs,gooningmoods,ecstasygoonsxxx,HornyJails_,SubbyGooner_," +
    "certigooners,goonsticklover,GoonGwynn,goonettearchive,Hornymilfv2," +
    "GoonGirlGalaxy,GoonetteChats,goonersgonewyld,goonettepupxd"

const val DEFAULT_ACCOUNTS =
    "AestheticBLJB,GoonHours1,Momhavevideo,lahlah084,DrakenAbyss,missAnju_,Hinosbs," +
    "itsJassminexoxo,Redgifs2,freakzzworld,DESI_BULL4,LewdVidsX,WeShowYouWomen,bf_jsr," +
    "Micro58609,mommysgoodi8o,tbth_oficial,rb0809669836773,bablacksheep6,FreaksLethal," +
    "latina_blowjobs,love_truelove_,Midnite_BDE,tetonaslindass,CouldaGoon,dark_love_2," +
    "goon2luna,flirtclubxo"

fun feedPrefs(context: Context) =
    context.getSharedPreferences(FEED_PREFS, Context.MODE_PRIVATE)

private fun parseNames(raw: String): List<String> =
    raw.split(",", "\n", " ")
        .map { it.trim().removePrefix("@") }
        .filter { it.isNotBlank() }
        .distinctBy { it.lowercase() }

fun feedPriority(context: Context): List<String> =
    parseNames(feedPrefs(context).getString(FEED_PRIORITY_KEY, null) ?: DEFAULT_PRIORITY)

// Regular accounts, without any that are already in the priority list.
fun feedAccounts(context: Context): List<String> {
    val priority = feedPriority(context).map { it.lowercase() }.toSet()
    return parseNames(feedPrefs(context).getString(FEED_ACCOUNTS_KEY, null) ?: DEFAULT_ACCOUNTS)
        .filter { it.lowercase() !in priority }
}

object RaindropFeedSettings {
    private fun label(context: Context, text: String, topPad: Int = 0) =
        TextView(context).apply {
            this.text = text
            textSize = 13f
            setPadding(0, topPad, 0, 0)
        }

    fun show(context: Context) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val prefs = feedPrefs(context)

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        layout.addView(label(context, "Raindrop token (test token from app.raindrop.io/settings/integrations)"))
        val tokenInput = EditText(context).apply {
            hint = "Raindrop token"
            setText(prefs.getString(FEED_TOKEN_KEY, "") ?: "")
        }
        layout.addView(tokenInput)

        layout.addView(label(context, "Priority accounts (comma separated). 5 random ones get a shelf at the top, rotating every 6 hours.", pad))
        val priorityInput = EditText(context).apply {
            hint = "user1,user2,user3"
            setText(prefs.getString(FEED_PRIORITY_KEY, null) ?: DEFAULT_PRIORITY)
        }
        layout.addView(priorityInput)

        layout.addView(label(context, "Other accounts (comma separated). 5 random ones get a shelf below, rotating every 6 hours.", pad))
        val accountsInput = EditText(context).apply {
            hint = "user1,user2,user3"
            setText(prefs.getString(FEED_ACCOUNTS_KEY, null) ?: DEFAULT_ACCOUNTS)
        }
        layout.addView(accountsInput)

        val scroll = ScrollView(context).apply { addView(layout) }

        AlertDialog.Builder(context)
            .setTitle("Raindrop Feed — Settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString(FEED_TOKEN_KEY, tokenInput.text.toString().trim())
                    .putString(FEED_PRIORITY_KEY, priorityInput.text.toString().trim())
                    .putString(FEED_ACCOUNTS_KEY, accountsInput.text.toString().trim())
                    .apply()
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
