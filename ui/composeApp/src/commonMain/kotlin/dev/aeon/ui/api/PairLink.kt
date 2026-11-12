package dev.aeon.ui.api

/**
 * `aeon://pair?u=<address>[&u=...]&c=<code>`, the text in a pairing QR code (port of src/core/pair-link.ts).
 * A link only suggests an address and a code: nothing is sent until the owner taps connect, and any app on the
 * phone can send such a link, so every part is checked and unsafe parts are dropped.
 */
data class PairLink(val addresses: List<String>, val code: String) {
    companion object {
        const val MAX_ADDRESSES = 4
        private val CODE = Regex("[A-Z2-7]{16}")

        /** True for addresses on a home or office network (private ranges, shared 100.64/10 used by VPNs, loopback, `.local`). */
        fun isLanAddress(origin: String): Boolean {
            val host = runCatching { io.ktor.http.Url(origin).host.lowercase() }.getOrNull() ?: return false
            if (host == "localhost" || host.endsWith(".local")) return true
            val parts = host.split('.')
            val p = parts.mapNotNull { it.toIntOrNull() }
            if (parts.size != 4 || p.size != 4) return false
            return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 100 && p[1] in 64..127)
        }

        /** protocol://host[:port], rebuilt from the parsed parts instead of reusing the text that arrived. */
        private fun normalised(s: String): String? {
            val u = runCatching { io.ktor.http.Url(s) }.getOrNull() ?: return null
            val port = if (u.specifiedPort != 0) ":${u.specifiedPort}" else ""
            val host = if (u.host.contains(':')) "[${u.host}]" else u.host
            return "${u.protocol.name}://$host$port"
        }

        private fun isHttpOrigin(s: String): Boolean {
            val u = runCatching { io.ktor.http.Url(s) }.getOrNull() ?: return false
            val http = u.protocol == io.ktor.http.URLProtocol.HTTP || u.protocol == io.ktor.http.URLProtocol.HTTPS
            return http && u.user == null && u.password == null && (u.encodedPath == "" || u.encodedPath == "/") && u.encodedQuery.isEmpty() && u.fragment.isEmpty() && u.host.isNotEmpty()
        }

        /** Percent-decoding of UTF-8 text. Returns null for a malformed escape. */
        fun decode(s: String): String? {
            val out = ArrayList<Byte>(s.length)
            var i = 0
            while (i < s.length) {
                if (s[i] == '%') {
                    val hex = if (i + 2 < s.length) s.substring(i + 1, i + 3) else return null
                    if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    out.add(hex.toInt(16).toByte())
                    i += 3
                } else {
                    val next = s.indexOf('%', i).let { if (it < 0) s.length else it }
                    out.addAll(s.substring(i, next).encodeToByteArray().toList())
                    i = next
                }
            }
            return out.toByteArray().decodeToString()
        }

        fun parse(link: String): PairLink? {
            val m = Regex("^aeon://pair\\?(.*)$").find(link.trim()) ?: return null
            val addresses = mutableListOf<String>()
            var code: String? = null
            for (part in m.groupValues[1].split("&")) {
                val eq = part.indexOf('=')
                if (eq < 0) continue
                val value = decode(part.substring(eq + 1)) ?: return null
                when (part.substring(0, eq)) {
                    "u" -> if (addresses.size < MAX_ADDRESSES && isHttpOrigin(if (value.endsWith("/")) value else "$value/")) normalised(value)?.let { addresses.add(it) }
                    "c" -> if (CODE.matches(value.uppercase())) code = value.uppercase()
                }
            }
            return code?.let { PairLink(addresses, it) }
        }

        private fun encode(s: String): String = encodeQueryValue(s)

        fun build(addresses: List<String>, code: String): String {
            val c = Signing.normalizeCode(code)
            require(CODE.matches(c)) { "pairing code must be 16 base32 characters" }
            val list = addresses.filter { isHttpOrigin(it) }.take(MAX_ADDRESSES)
            return "aeon://pair?" + list.joinToString("") { "u=${encode(it.trimEnd('/'))}&" } + "c=$c"
        }
    }
}
