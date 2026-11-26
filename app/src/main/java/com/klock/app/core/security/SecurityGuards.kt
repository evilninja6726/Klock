package com.klock.app.core.security

import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * Centralized security validation utilities:
 * - Enforces HTTPS-only external source and API endpoints.
 * - Blocks SSRF targets (loopback, RFC1918 private networks, link-local, CGNAT, cloud metadata IPs,
 *   file://, content://, javascript:, data: schemes).
 * - Generates cryptographic SHA-256 question fingerprints and non-guessable emergency override codes.
 */
object SecurityGuards {

    private val secureRandom = SecureRandom()

    private val ALLOWED_SOURCE_DOMAINS = setOf(
        "en.wikipedia.org",
        "wikipedia.org",
        "learn.microsoft.com",
        "stackoverflow.com",
        "api.stackexchange.com",
        "dba.stackexchange.com",
        "economics.stackexchange.com",
        "stats.stackexchange.com",
        "opentdb.com",
        "the-trivia-api.com"
    )

    fun isValidPublicHttpsUrl(
        rawUrl: String,
        allowLoopbackForTesting: Boolean = false
    ): Boolean {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) return false
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return false
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return false
        if (scheme != "https" && !(allowLoopbackForTesting && scheme == "http")) {
            return false
        }
        if (uri.userInfo != null) return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trim('.') ?: return false
        if (host.isEmpty()) return false

        if (allowLoopbackForTesting && (host == "localhost" || host == "127.0.0.1")) {
            return true
        }

        if (host == "localhost" ||
            host.endsWith(".local") ||
            host.endsWith(".internal") ||
            host == "metadata.google.internal"
        ) {
            return false
        }

        // Reject literal IP literals that map to loopback, private, link-local, or cloud metadata ranges
        if (looksLikeIpLiteral(host)) {
            val address = try {
                InetAddress.getByName(host)
            } catch (_: Exception) {
                return false
            }
            if (isDisallowedIpAddress(address)) return false
        }

        return true
    }

    fun isTrustedEducationalCitationUrl(
        rawUrl: String,
        allowLoopbackForTesting: Boolean = false
    ): Boolean {
        if (!isValidPublicHttpsUrl(rawUrl, allowLoopbackForTesting)) return false
        val host = URI(rawUrl.trim()).host?.lowercase(Locale.ROOT)?.trim('.') ?: return false
        if (allowLoopbackForTesting && (host == "localhost" || host == "127.0.0.1")) return true
        return ALLOWED_SOURCE_DOMAINS.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
    }

    fun isDisallowedIpAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress ||
            address.isAnyLocalAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        if (bytes.size == 4) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            // 100.64.0.0/10 CGNAT, 169.254.0.0/16 cloud metadata, 127.0.0.0/8, 0.0.0.0/8
            if (b0 == 0 || b0 == 127) return true
            if (b0 == 100 && b1 in 64..127) return true
            if (b0 == 169 && b1 == 254) return true
            if (b0 == 192 && b1 == 168) return true
            if (b0 == 172 && b1 in 16..31) return true
            if (b0 == 10) return true
        } else if (bytes.size == 16) {
            // Unique local fc00::/7 or IPv4-mapped ::ffff:x.x.x.x
            val b0 = bytes[0].toInt() and 0xFF
            if ((b0 and 0xFE) == 0xFC) return true
            val isV4Mapped = (0..9).all { bytes[it].toInt() == 0 } &&
                (bytes[10].toInt() and 0xFF) == 0xFF &&
                (bytes[11].toInt() and 0xFF) == 0xFF
            if (isV4Mapped) {
                val ipv4 = InetAddress.getByAddress(bytes.sliceArray(12..15))
                return isDisallowedIpAddress(ipv4)
            }
        }
        return false
    }

    private fun looksLikeIpLiteral(host: String): Boolean {
        if (host.contains(':')) return true // IPv6
        // Standard dotted quad, hex/octal numeric host, or pure integer IP
        return host.all { it.isDigit() || it == '.' || it == 'x' || it == 'X' || it in 'a'..'f' }
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        val chars = CharArray(digest.size * 2)
        val hex = "0123456789abcdef"
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            chars[i * 2] = hex[v ushr 4]
            chars[i * 2 + 1] = hex[v and 0x0F]
        }
        return String(chars)
    }

    fun computeQuestionFingerprint(prompt: String, canonicalAnswer: String): String {
        val normalizedPrompt = prompt.lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
        val normalizedAnswer = canonicalAnswer.lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
        return sha256Hex("$normalizedPrompt||$normalizedAnswer").take(32)
    }

    fun generateEmergencyOverrideCode(): String {
        val alphabet = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val part = buildString(6) {
            repeat(6) {
                append(alphabet[secureRandom.nextInt(alphabet.length)])
            }
        }
        return "KLOCK-$part"
    }
}
