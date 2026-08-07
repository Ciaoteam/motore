package org.acme.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class ConstraintIdSanitizerTest {

    private static final Pattern ALLOWED_CONSTRAINT_ID_PATTERN = Pattern.compile("[A-Za-z0-9 _'()\-.]+");

    @Test
    void sanitizeReplacesInvalidCharacters() {
        String sanitizedConstraintId = ConstraintIdSanitizer.sanitize("Goal: target shifts per employee per week [HARD]/week");

        assertEquals("Goal- target shifts per employee per week -HARD--week", sanitizedConstraintId);
        assertTrue(ALLOWED_CONSTRAINT_ID_PATTERN.matcher(sanitizedConstraintId).matches());
    }

    @Test
    void sanitizeLeavesAllowedCharactersUntouched() {
        String constraintId = "Teacher room stability (SOFT).";

        assertEquals(constraintId, ConstraintIdSanitizer.sanitize(constraintId));
    }
}
