package com.xiaoshuo.yijianhuanming.reader

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.xiaoshuo.yijianhuanming.content.web.LoginRiskPolicy
import com.xiaoshuo.yijianhuanming.data.ReplaceRule
import com.xiaoshuo.yijianhuanming.data.forRuntime
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.json.JSONTokener

interface RuleRuntime {
    suspend fun applyRules(rules: List<ReplaceRule>): Result<RuleApplyResult>

    suspend fun restoreOriginalText(): Result<Unit> = applyRules(emptyList()).map {}
}

data class RuleMatchCount(
    val ruleId: String,
    val replacementCount: Int,
)

data class RuleApplyResult(
    val activeRuleCount: Int,
    val changedTextNodeCount: Int,
    val replacementCount: Int,
    val perRule: List<RuleMatchCount>,
)

class RuntimeResultParser {
    fun parse(raw: String?): Result<RuleApplyResult> = runCatching {
        require(!raw.isNullOrBlank() && raw != "null") { "Runtime returned no result" }
        val decoded = JSONTokener(raw).nextValue()
        val payload = when (decoded) {
            is String -> JSONObject(decoded)
            is JSONObject -> decoded
            else -> error("Runtime returned malformed JSON")
        }
        check(payload.optBoolean("ok", false)) {
            payload.optString("message", "Runtime rejected the operation")
        }

        val perRuleJson = payload.getJSONArray("perRule")
        val perRule = buildList {
            repeat(perRuleJson.length()) { index ->
                val item = perRuleJson.getJSONObject(index)
                val count = item.getInt("replacementCount")
                require(count >= 0) { "Rule replacement count must be non-negative" }
                add(
                    RuleMatchCount(
                        ruleId = item.getString("ruleId"),
                        replacementCount = count,
                    ),
                )
            }
        }
        val result = RuleApplyResult(
            activeRuleCount = payload.getInt("activeRuleCount"),
            changedTextNodeCount = payload.getInt("changedTextNodeCount"),
            replacementCount = payload.getInt("replacementCount"),
            perRule = perRule,
        )
        require(result.activeRuleCount >= 0) { "Active rule count must be non-negative" }
        require(result.changedTextNodeCount >= 0) { "Changed node count must be non-negative" }
        require(result.replacementCount >= 0) { "Replacement count must be non-negative" }
        require(result.perRule.sumOf(RuleMatchCount::replacementCount) == result.replacementCount) {
            "Per-rule counts do not match replacement total"
        }
        result
    }
}

class WebRuntimeScriptEncoder {
    fun applyRules(rules: List<ReplaceRule>): String {
        val payload = rules.forRuntime().joinToString(prefix = "[", postfix = "]") { rule ->
            """{"id":${quote(rule.id)},"source":${quote(rule.source)},"target":${quote(rule.target)},"order":${rule.order}}"""
        }
        return "window.__NAME_REPLACER__.applyRules(JSON.parse(${quote(payload)}))"
    }

    fun extractPayload(script: String): String {
        val start = script.indexOf("JSON.parse(")
        require(start >= 0) { "Script has no JSON payload" }
        val encoded = script.substring(start + "JSON.parse(".length, script.lastIndexOf("))"))
        return decodeQuotedString(encoded)
    }

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003C")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (character.code < 0x20) {
                    append("\\u%04x".format(character.code))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun decodeQuotedString(value: String): String {
        require(value.length >= 2 && value.first() == '"' && value.last() == '"')
        val result = StringBuilder()
        var index = 1
        while (index < value.lastIndex) {
            val character = value[index++]
            if (character != '\\') {
                result.append(character)
                continue
            }
            when (val escaped = value[index++]) {
                '"', '\\', '/' -> result.append(escaped)
                'b' -> result.append('\b')
                'f' -> result.append('\u000C')
                'n' -> result.append('\n')
                'r' -> result.append('\r')
                't' -> result.append('\t')
                'u' -> {
                    result.append(value.substring(index, index + 4).toInt(16).toChar())
                    index += 4
                }
                else -> error("Invalid JSON escape: $escaped")
            }
        }
        return result.toString()
    }
}

class NavigationGenerations {
    private val value = AtomicLong(0)

    fun beginNavigation(): Long = value.incrementAndGet()

    fun invalidate() {
        value.incrementAndGet()
    }

    fun isCurrent(generation: Long): Boolean = value.get() == generation

    fun current(): Long = value.get()
}

class WebRuntimeController(
    private val webView: WebView,
    private val generations: NavigationGenerations = NavigationGenerations(),
    private val encoder: WebRuntimeScriptEncoder = WebRuntimeScriptEncoder(),
    private val resultParser: RuntimeResultParser = RuntimeResultParser(),
) : RuleRuntime {
    fun beginNavigation(): Long = generations.beginNavigation()

    fun inspectLoginRisk(generation: Long, onResult: (Boolean) -> Unit) {
        evaluateForGeneration(
            generation = generation,
            script = LoginRiskPolicy.PASSWORD_FORM_GUARD_SCRIPT,
        ) { result ->
            onResult(LoginRiskPolicy.resultHasLoginRisk(result))
        }
    }

    fun installRuntime(generation: Long, onResult: (Boolean) -> Unit = {}) {
        val runtime = runCatching {
            webView.context.assets.open(RUNTIME_ASSET).bufferedReader().use { it.readText() }
        }.getOrElse {
            if (generations.isCurrent(generation)) onResult(false)
            return
        }
        evaluateForGeneration(generation, runtime) {
            onResult(true)
        }
    }

    fun applyRules(
        generation: Long,
        rules: List<ReplaceRule>,
        onResult: (Result<RuleApplyResult>) -> Unit,
    ) {
        val operation = encoder.applyRules(rules)
        val script = """
            (function() {
              try {
                if (!window.__NAME_REPLACER__) {
                  return JSON.stringify({
                    ok: false,
                    code: "NOT_INSTALLED",
                    message: "Name replacer runtime is not installed"
                  });
                }
                const result = $operation;
                return JSON.stringify(result);
              } catch (error) {
                return JSON.stringify({
                  ok: false,
                  code: "RUNTIME_ERROR",
                  message: String(error)
                });
              }
            })()
        """.trimIndent()
        evaluateForGeneration(generation, script) { result ->
            onResult(resultParser.parse(result))
        }
    }

    override suspend fun applyRules(rules: List<ReplaceRule>): Result<RuleApplyResult> =
        evaluateCurrentRules(rules)

    private suspend fun evaluateCurrentRules(rules: List<ReplaceRule>): Result<RuleApplyResult> =
        suspendCancellableCoroutine { continuation ->
            val generation = generations.current()
            applyRules(generation, rules) { result ->
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }

    fun evaluateForGeneration(
        generation: Long,
        script: String,
        onResult: (String?) -> Unit,
    ) {
        if (!generations.isCurrent(generation)) return
        webView.evaluateJavascript(script) { result ->
            if (generations.isCurrent(generation)) {
                onResult(result)
            }
        }
    }

    fun clearRemoteSession() {
        generations.invalidate()
        webView.stopLoading()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        webView.clearFormData()
        webView.clearCache(true)
        webView.clearHistory()
    }

    fun invalidate() {
        generations.invalidate()
    }

    companion object {
        private const val RUNTIME_ASSET = "name-replacer.js"
    }
}
