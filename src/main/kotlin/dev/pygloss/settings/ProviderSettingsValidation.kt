package dev.pygloss.settings

import java.net.URI

/** Return a user-facing validation error, or null when provider inputs are usable. */
internal fun validateProviderSettings(baseUrl: String, model: String): String? {
    val endpoint = baseUrl.trim()
    if (endpoint.isBlank()) return "Base URL is required."
    if (!endpoint.contains("://")) return "Base URL must be a complete http:// or https:// address."

    val uri = runCatching { URI(endpoint) }.getOrNull()
        ?: return "Base URL must be a complete http:// or https:// address."
    val scheme = uri.scheme?.lowercase()
    if (scheme !in setOf("http", "https")) return "Base URL must use http:// or https://."
    if (uri.host.isNullOrBlank()) return "Base URL must be a complete http:// or https:// address."
    if (scheme == "http" && !isLoopbackBaseUrl(endpoint)) {
        return "Remote provider URLs must use https:// to protect source code and API keys."
    }
    if (model.isBlank()) return "Model is required."
    return null
}

/** Whether [baseUrl] targets the local machine and may safely use plain HTTP. */
internal fun isLoopbackBaseUrl(baseUrl: String): Boolean {
    val host = runCatching { URI(baseUrl).host?.lowercase()?.trim('[', ']') }.getOrNull() ?: return false
    return host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0.0.0.0"
}
