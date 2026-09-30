package org.opencell.core.dev

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The static developer access code (decided 2026-09-30): a speed bump, not security. */
class DeveloperAccessTest {
    @Test
    fun acceptsTheCode() {
        assertTrue(DeveloperAccess.isCorrect("67362355"))
    }

    @Test
    fun trimsSurroundingWhitespace() {
        assertTrue(DeveloperAccess.isCorrect(" 67362355 "))
        assertTrue(DeveloperAccess.isCorrect("\t67362355\n"))
    }

    @Test
    fun rejectsTheEmptyString() {
        assertFalse(DeveloperAccess.isCorrect(""))
        assertFalse(DeveloperAccess.isCorrect("   "))
    }

    @Test
    fun rejectsAnythingOtherThanTheExactCode() {
        assertFalse(DeveloperAccess.isCorrect("1234"))
        assertFalse(DeveloperAccess.isCorrect("6736235")) // one short
        assertFalse(DeveloperAccess.isCorrect("673623550")) // one long
        assertFalse(DeveloperAccess.isCorrect("67362356")) // off by one
        assertFalse(DeveloperAccess.isCorrect("OPENCELL")) // the letters, not the digits
    }

    @Test
    fun isCaseAndFormInsensitiveOnlyThroughTrimming() {
        // No digit grouping or punctuation is accepted: it's a plain numeric field.
        assertFalse(DeveloperAccess.isCorrect("673-623-55"))
    }
}
