package org.acme.common;

import java.util.Objects;
import java.util.regex.Pattern;

public final class ConstraintIdSanitizer {

    private static final Pattern DISALLOWED_CHARACTERS = Pattern.compile("[^A-Za-z0-9 _'().-]");

    private ConstraintIdSanitizer() {
    }

    public static String sanitize(String constraintId) {
        Objects.requireNonNull(constraintId, "constraintId must not be null");
        return DISALLOWED_CHARACTERS.matcher(constraintId).replaceAll("-");
    }
}
