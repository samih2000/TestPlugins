package com.lagradost

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

const val TWACC_AUTH_TOKEN_KEY = "twacc_auth_token"
const val TWACC_CT0_KEY = "twacc_ct0"
const val TWACC_USERNAMES_KEY = "twacc_usernames"
private const val TWACC_PREFS_NAME = "twitter_accounts_settings"

fun twAccPrefs(context: Context) =
    context.getSharedPreferences(TWACC_PREFS_NAME, Context.MODE_PRIVATE)

fun twAccUsernames(context: Context): List<String> =
    twAccPrefs(context).getString(TWACC_USERNAMES_KEY, "")
        ?.lines()
        ?.map { it.trim().removePrefix("@") }
        ?.filter { it.isNotBlank() }
        ?: emptyList()

object TwitterAccountsSettings {
    private fun addSingleLineField(
        context: Context, layout: LinearLayout, pad: Int, label: String, key: String
    ) {
        layout.addView(TextView(context).apply {
            text = label
            textSize = 13f
            setPadding(0, pad, 0, pad / 2)
        })
        val current = twAccPrefs(context).getString(key, "") ?: ""
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
            text = "X/Twitter accounts to browse — one username per line, no @ needed, add as many as you like."
            textSize = 13f
        })
        val currentUsernames = twAccPrefs(context).getString(TWACC_USERNAMES_KEY, "") ?: ""
        val usernamesInput = EditText(context).apply {
            hint = "e.g.\nexampleuser1\nexampleuser2"
            setText(currentUsernames)
            minLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        layout.addView(usernamesInput)
        layout.setTag(TWACC_USERNAMES_KEY.hashCode(), usernamesInput)

        layout.addView(TextView(context).apply {
            text = "\nX/Twitter session cookies (required — this plugin only works logged in). " +
                    "From a logged-in browser: DevTools → Application → Cookies → x.com."
            textSize = 13f
            setPadding(0, pad, 0, 0)
        })
        addSingleLineField(context, layout, pad, "auth_token cookie", TWACC_AUTH_TOKEN_KEY)
        addSingleLineField(context, layout, pad, "ct0 cookie", TWACC_CT0_KEY)

        AlertDialog.Builder(context)
            .setTitle("Twitter Accounts — Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val prefs = twAccPrefs(context).edit()
                listOf(TWACC_USERNAMES_KEY, TWACC_AUTH_TOKEN_KEY, TWACC_CT0_KEY).forEach { key ->
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
