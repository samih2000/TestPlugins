package com.lagradost.rainfeed

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

const val FEED_TOKEN_KEY = "feed_raindrop_token"
const val FEED_ACCOUNTS_KEY = "feed_accounts"
private const val FEED_PREFS = "raindrop_feed_settings"

// Used until the accounts list is edited in settings. Keep in sync with tools/xsync/accounts.json.
const val DEFAULT_ACCOUNTS = "lahlah084,itsJassminexoxo,flirtclubxo"

fun feedPrefs(context: Context) =
    context.getSharedPreferences(FEED_PREFS, Context.MODE_PRIVATE)

fun feedAccounts(context: Context): List<String> =
    (feedPrefs(context).getString(FEED_ACCOUNTS_KEY, null) ?: DEFAULT_ACCOUNTS)
        .split(",", "\n", " ")
        .map { it.trim().removePrefix("@") }
        .filter { it.isNotBlank() }
        .distinct()

object RaindropFeedSettings {
    fun show(context: Context) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        val prefs = feedPrefs(context)

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        layout.addView(TextView(context).apply {
            text = "Raindrop token (test token from app.raindrop.io/settings/integrations)"
            textSize = 13f
        })
        val tokenInput = EditText(context).apply {
            hint = "Raindrop token"
            setText(prefs.getString(FEED_TOKEN_KEY, "") ?: "")
        }
        layout.addView(tokenInput)

        layout.addView(TextView(context).apply {
            text = "\nAccounts, one shelf each (comma separated, same names as in the sync job)"
            textSize = 13f
        })
        val accountsInput = EditText(context).apply {
            hint = "user1,user2,user3"
            setText(prefs.getString(FEED_ACCOUNTS_KEY, null) ?: DEFAULT_ACCOUNTS)
        }
        layout.addView(accountsInput)

        AlertDialog.Builder(context)
            .setTitle("Raindrop Feed — Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString(FEED_TOKEN_KEY, tokenInput.text.toString().trim())
                    .putString(FEED_ACCOUNTS_KEY, accountsInput.text.toString().trim())
                    .apply()
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
