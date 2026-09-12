package com.lagradost

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

const val RAINDROP_TOKEN_KEY = "raindrop_token"
const val TWITTER_AUTH_TOKEN_KEY = "twitter_auth_token"
const val TWITTER_CT0_KEY = "twitter_ct0"
private const val PREFS_NAME = "raindrop_provider_settings"

fun raindropPrefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

object RaindropSettings {
    private fun addField(
        context: Context,
        layout: LinearLayout,
        pad: Int,
        label: String,
        key: String
    ) {
        layout.addView(TextView(context).apply {
            text = label
            textSize = 13f
            setPadding(0, pad, 0, pad / 2)
        })

        val current = raindropPrefs(context).getString(key, "") ?: ""
        val input = EditText(context).apply {
            hint = label
            setText(current)
        }
        layout.addView(input)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        layout.addView(TextView(context).apply {
            text = "📋 Paste from clipboard"
            setPadding(0, pad / 2, 0, 0)
            isClickable = true
            setOnClickListener {
                val clip = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
                if (!clip.isNullOrBlank()) {
                    input.setText(clip)
                    input.setSelection(input.text?.length ?: 0)
                } else {
                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            }
        })

        layout.setTag(key.hashCode(), input)
    }

    fun show(context: Context) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        layout.addView(TextView(context).apply {
            text = "Raindrop token (app.raindrop.io/settings/integrations → your app → Create test token)"
            textSize = 13f
        })
        addField(context, layout, pad, "Raindrop token", RAINDROP_TOKEN_KEY)

        layout.addView(TextView(context).apply {
            text = "\nX/Twitter cookies (optional — unlocks age-restricted videos). " +
                    "From a logged-in browser: DevTools → Application → Cookies → x.com."
            textSize = 13f
            setPadding(0, pad, 0, 0)
        })
        addField(context, layout, pad, "auth_token cookie", TWITTER_AUTH_TOKEN_KEY)
        addField(context, layout, pad, "ct0 cookie", TWITTER_CT0_KEY)

        AlertDialog.Builder(context)
            .setTitle("Raindrop Videos — Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val prefs = raindropPrefs(context).edit()
                listOf(RAINDROP_TOKEN_KEY, TWITTER_AUTH_TOKEN_KEY, TWITTER_CT0_KEY).forEach { key ->
                    val input = layout.getTag(key.hashCode()) as? EditText
                    prefs.putString(key, input?.text?.toString()?.trim() ?: "")
                }
                prefs.apply()
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
