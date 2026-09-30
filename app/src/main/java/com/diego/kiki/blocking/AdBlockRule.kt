package com.diego.kiki.blocking

sealed interface AdBlockRule {
    val rawRule: String

    data class DomainRule(
        override val rawRule: String,
        val domain: String
    ) : AdBlockRule

    data class SubstringRule(
        override val rawRule: String,
        val substring: String
    ) : AdBlockRule

    data class ExceptionRule(
        override val rawRule: String,
        val domain: String
    ) : AdBlockRule
}
