package com.xiaoshuo.yijianhuanming.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "rules")
data class RuleEntity(
    @PrimaryKey val id: String,
    val source: String,
    val target: String,
    val position: Int,
)

data class ReplaceRule(
    val id: String,
    val source: String,
    val target: String,
    val order: Int,
)

fun normalizeRules(rules: List<ReplaceRule>): List<ReplaceRule> =
    rules
        .map { it.copy(source = sharedTrim(it.source), target = sharedTrim(it.target)) }
        .filter { it.source.isNotEmpty() && it.target.isNotEmpty() }
        .sortedBy { it.order }

fun List<ReplaceRule>.forRuntime(): List<ReplaceRule> =
    sortedWith(compareByDescending<ReplaceRule> { it.source.length }.thenBy { it.order })

internal fun ReplaceRule.toEntity() = RuleEntity(id, source, target, order)

internal fun RuleEntity.toModel() = ReplaceRule(id, source, target, position)
