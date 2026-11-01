package com.lagradost.rainfeed

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject

const val FEED_TOKEN_KEY = "feed_raindrop_token"
const val FEED_CONFIG_KEY = "feed_config_json"
const val FEED_CONFIG_TS_KEY = "feed_config_ts"
private const val FEED_PREFS = "raindrop_feed_settings"

// The one and only account list, shared with the daily sync job.
const val ACCOUNTS_URL =
    "https://raw.githubusercontent.com/samih2000/TestPlugins/main/tools/xsync/accounts.json"

fun feedPrefs(context: Context) =
    context.getSharedPreferences(FEED_PREFS, Context.MODE_PRIVATE)

private fun namesFromConfig(context: Context, key: String): List<String> {
    val raw = feedPrefs(context).getString(FEED_CONFIG_KEY, null) ?: return emptyList()
    return try {
        val arr = JSONObject(raw).optJSONArray(key) ?: return emptyList()
        (0 until arr.length())
            .map { arr.optString(it).trim().removePrefix("@") }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    } catch (e: Exception) {
        emptyList()
    }
}

fun feedPriority(context: Context): List<String> = namesFromConfig(context, "priority")

// Regular accounts, without any that are already in the priority list.
fun feedAccounts(context: Context): List<String> {
    val priority = feedPriority(context).map { it.lowercase() }.toSet()
    return namesFromConfig(context, "accounts").filter { it.lowercase() !in priority }
}

object RaindropFeedSettings {
    fun show(context: Context) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val prefs = feedPrefs(context)

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        layout.addView(TextView(context).apply {
            text = "Raindrop token (test token from app.raindrop.io/settings/integrations)\n" +
                    "Account lists come from tools/xsync/accounts.json in the repo."
            textSize = 13f
        })
        val tokenInput = EditText(context).apply {
            hint = "Raindrop token"
            setText(prefs.getString(FEED_TOKEN_KEY, "") ?: "")
        }
        layout.addView(tokenInput)

        AlertDialog.Builder(context)
            .setTitle("Raindrop Feed — Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString(FEED_TOKEN_KEY, tokenInput.text.toString().trim())
                    .putLong(FEED_CONFIG_TS_KEY, 0L) // re-download the lists on next open
                    .apply()
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
