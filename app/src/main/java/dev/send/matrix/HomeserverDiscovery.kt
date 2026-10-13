package dev.send.matrix

/**
 * Accepts whatever a person (or password manager) pastes as a server address:
 * a full URL (`https://matrix.example.org`), a bare server name (`reilly.asia`,
 * optionally with `:port`), or a Matrix ID (`@james:reilly.asia`, `@` optional).
 * Returns the server name, or null when the input is not in user-ID form.
 */
internal fun serverNameFromMxid(value: String): String? {
    val input = value.trim()
    if (input.startsWith("@")) {
        val colon = input.indexOf(':', 1)
        if (colon < 2 || colon == input.length - 1) return null
        return input.substring(colon + 1).takeIf { it.isValidServerName() }
    }
    // Tolerate a missing "@" sigil ("james:reilly.asia" -> "reilly.asia").
    // A "host:port" pair keeps numeric ports, so only a dotted, non-numeric
    // suffix counts as a server name.
    val colon = input.lastIndexOf(':')
    if (colon > 0 && "://" !in input && '/' !in input) {
        val suffix = input.substring(colon + 1)
        if (suffix.contains('.') && !suffix.all(Char::isDigit) && suffix.isValidServerName()) return suffix
    }
    return null
}

internal fun String.isValidServerName(): Boolean =
    isNotEmpty() && none { it.isWhitespace() || it == '/' || it == '?' || it == '#' || it == '@' }

/**
 * Password-manager path: when the server field is blank but the username is a
 * full Matrix ID, the server comes from the username.
 */
internal fun effectiveServerInput(homeserver: String, user: String): String =
    homeserver.trim().ifBlank { serverNameFromMxid(user).orEmpty() }
