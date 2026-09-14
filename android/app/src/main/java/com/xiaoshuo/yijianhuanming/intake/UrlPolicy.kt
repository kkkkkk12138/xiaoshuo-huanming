package com.xiaoshuo.yijianhuanming.intake

import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

sealed interface UrlDecision {
    data object Allow : UrlDecision
    data object ConfirmCleartext : UrlDecision
    data class Reject(
        val reason: String,
        val kind: UrlRejectKind,
    ) : UrlDecision
}

enum class UrlRejectKind {
    INVALID,
    UNSAFE,
}

class UrlPolicy {
    fun evaluate(rawUrl: String): UrlDecision {
        val uri = runCatching { URI(normalize(rawUrl)) }.getOrNull()
            ?: return UrlDecision.Reject("链接格式无效", UrlRejectKind.INVALID)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return UrlDecision.Reject("仅支持 HTTP/HTTPS 链接", UrlRejectKind.UNSAFE)
        }
        if (!uri.isAbsolute || uri.host.isNullOrBlank()) {
            return UrlDecision.Reject("链接缺少有效的公网主机", UrlRejectKind.INVALID)
        }
        if (uri.rawUserInfo != null) {
            return UrlDecision.Reject("链接不能包含账号凭据", UrlRejectKind.UNSAFE)
        }
        if (runCatching { uri.port }.getOrElse { -2 } == -2) {
            return UrlDecision.Reject("链接端口无效", UrlRejectKind.INVALID)
        }

        val host = uri.host.lowercase().trimEnd('.')
        if (isLocalName(host) || isUnsafeIpLiteral(host)) {
            return UrlDecision.Reject("不允许访问本机或私网地址", UrlRejectKind.UNSAFE)
        }
        return if (scheme == "https") UrlDecision.Allow else UrlDecision.ConfirmCleartext
    }

    fun normalize(rawUrl: String): String = rawUrl.trim(*URL_BOUNDARY_WHITESPACE)

    private fun isLocalName(host: String): Boolean =
        host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")

    private fun isUnsafeIpLiteral(host: String): Boolean {
        if (host.contains(':')) {
            val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return true
            return address !is Inet6Address ||
                address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.address.firstOrNull()?.toInt()?.and(0xfe) == 0xfc
        }

        if (!host.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))) {
            // Reject numeric/hex aliases such as 127.1, 2130706433 and 0x7f000001.
            return host.matches(Regex("""(?:0x[0-9a-f]+|\d+)(?:\.(?:0x[0-9a-f]+|\d+))*"""))
        }
        val octets = host.split('.').map { it.toIntOrNull() ?: return true }
        if (octets.any { it !in 0..255 }) return true
        return octets[0] == 0 ||
            octets[0] == 10 ||
            octets[0] == 127 ||
            octets[0] >= 224 ||
            octets[0] == 169 && octets[1] == 254 ||
            octets[0] == 172 && octets[1] in 16..31 ||
            octets[0] == 192 && octets[1] == 168 ||
            octets[0] == 100 && octets[1] in 64..127
    }

    private companion object {
        val URL_BOUNDARY_WHITESPACE = charArrayOf(
            '\u0009', '\u000A', '\u000B', '\u000C', '\u000D', '\u0020',
            '\u0085', '\u00A0', '\u1680', '\u2000', '\u2001', '\u2002',
            '\u2003', '\u2004', '\u2005', '\u2006', '\u2007', '\u2008',
            '\u2009', '\u200A', '\u2028', '\u2029', '\u202F', '\u205F',
            '\u3000', '\uFEFF',
        )
    }
}
