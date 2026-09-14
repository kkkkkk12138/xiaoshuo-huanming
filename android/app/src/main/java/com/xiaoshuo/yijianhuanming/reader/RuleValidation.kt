package com.xiaoshuo.yijianhuanming.reader

import com.xiaoshuo.yijianhuanming.data.ReplaceRule
import com.xiaoshuo.yijianhuanming.data.sharedTrim

enum class RuleValidationError {
    SOURCE_REQUIRED,
    TARGET_REQUIRED,
    SAME_VALUE,
    DUPLICATE_SOURCE,
}

data class ValidatedRules(
    val normalized: List<ReplaceRule>,
    val errors: Map<String, RuleValidationError>,
)

fun validateRules(rules: List<ReplaceRule>): ValidatedRules {
    val normalized = rules
        .map { rule ->
            rule.copy(
                source = sharedTrim(rule.source),
                target = sharedTrim(rule.target),
            )
        }
        .sortedBy(ReplaceRule::order)

    val errors = linkedMapOf<String, RuleValidationError>()
    normalized.forEach { rule ->
        when {
            rule.source.isEmpty() -> errors[rule.id] = RuleValidationError.SOURCE_REQUIRED
            rule.target.isEmpty() -> errors[rule.id] = RuleValidationError.TARGET_REQUIRED
            rule.source == rule.target -> errors[rule.id] = RuleValidationError.SAME_VALUE
        }
    }

    normalized
        .filterNot { errors.containsKey(it.id) }
        .groupBy(ReplaceRule::source)
        .values
        .filter { matches -> matches.size > 1 }
        .flatten()
        .forEach { rule -> errors[rule.id] = RuleValidationError.DUPLICATE_SOURCE }

    return ValidatedRules(normalized = normalized, errors = errors)
}
