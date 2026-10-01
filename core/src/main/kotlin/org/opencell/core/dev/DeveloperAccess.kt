package org.opencell.core.dev

/**
 * The static code that unlocks OpenCell's developer features (Console,
 * Loopback, the demo terminal, the call screen's voice stats line):
 * `67362355`, "OPENCELL" on a phone keypad. Decided 2026-09-30. It lives in
 * the public source — same as the iOS app's copy — so this is a speed bump
 * against clutter for ordinary users, not a security boundary: no lockout on
 * a wrong try, and the comparison is a plain string equality.
 */
object DeveloperAccess {
    const val CODE = "67362355"

    /** True if [input], once its surrounding whitespace is trimmed, is the code. */
    fun isCorrect(input: String): Boolean = input.trim() == CODE
}
