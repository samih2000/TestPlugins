package com.lagradost

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

const val RAINDROP_TOKEN_KEY = "raindrop_token"
private const val PREFS_NAME = "raindrop_provider_settings"

fun raindropPrefs(context: Context) =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

object RaindropSettings {
    fun show(context: Context) {
        val pad = (16 * context.resources.displayMetrics.density).toInt()

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        layout.addView(TextView(context).apply {
            text = "Paste your Raindrop.io test token " +
                    "(app.raindrop.io/settings/integrations → your app → Create test token)."
            textSize = 13f
            setPadding(0, 0, 0, pad)
        })

        val currentToken = raindropPrefs(context).getString(RAINDROP_TOKEN_KEY, "") ?: ""
        val input = EditText(context).apply {
            hint = "Raindrop token"
            setText(currentToken)
        }
        layout.addView(input)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        layout.addView(TextView(context).apply {
            text = "📋 Paste from clipboard"
            setPadding(0, pad, 0, 0)
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

        AlertDialog.Builder(context)
            .setTitle("Raindrop Videos — Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                raindropPrefs(context).edit()
                    .putString(RAINDROP_TOKEN_KEY, input.text.toString().trim())
                    .apply()
                Toast.makeText(context, "Saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
