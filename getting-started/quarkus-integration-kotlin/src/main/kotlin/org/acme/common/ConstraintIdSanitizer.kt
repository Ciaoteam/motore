package org.acme.common

object ConstraintIdSanitizer {

    private val disallowedCharacters = Regex("[^A-Za-z0-9 _'().-]")

    fun sanitize(constraintId: String): String = constraintId.replace(disallowedCharacters, "-")
}
