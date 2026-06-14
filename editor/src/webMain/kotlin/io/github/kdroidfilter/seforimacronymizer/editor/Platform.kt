package io.github.kdroidfilter.seforimacronymizer.editor

import kotlinx.browser.document
import kotlinx.browser.localStorage
import kotlinx.browser.window

private const val COOKIE_TOKEN = "gh_token"

/** Persists the GitHub token in a cookie (the app is a fully static client-side site). */
object TokenStore {
    fun load(): String? {
        val raw = document.cookie
        if (raw.isBlank()) return null
        for (part in raw.split(";")) {
            val t = part.trim()
            val prefix = "$COOKIE_TOKEN="
            if (t.startsWith(prefix)) return t.substring(prefix.length).ifBlank { null }
        }
        return null
    }

    fun save(token: String) {
        // GitHub tokens are cookie-safe (no ';', ',' or whitespace), so no encoding needed.
        document.cookie = "$COOKIE_TOKEN=$token; path=/; max-age=31536000; SameSite=Strict"
    }

    fun clear() {
        document.cookie = "$COOKIE_TOKEN=; path=/; max-age=0; SameSite=Strict"
    }
}

fun openUrl(url: String) {
    window.open(url, "_blank")
}

/** Monotonic suffix (persisted) used to build unique PR branch names without relying on wall-clock. */
fun nextBranchSuffix(): Int {
    val key = "pr_counter"
    val n = (localStorage.getItem(key)?.toIntOrNull() ?: 0) + 1
    localStorage.setItem(key, n.toString())
    return n
}
