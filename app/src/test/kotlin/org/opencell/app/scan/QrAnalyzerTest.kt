package org.opencell.app.scan

import org.junit.Assert.assertEquals
import org.junit.Test

/** Plain JVM: the analyzer reports a code once while it stays in view, not on every frame. */
class QrAnalyzerTest {
    @Test
    fun aRepeatedIdenticalTextIsReportedOnce() {
        val seen = mutableListOf<String>()
        val analyzer = QrAnalyzer { seen += it }
        analyzer.report("opencell:1:nope")
        analyzer.report("opencell:1:nope") // the same (invalid) code, next frame
        analyzer.report(null) // a frame without a code
        analyzer.report("opencell:1:nope")
        analyzer.report("something else")
        analyzer.report("opencell:1:nope")
        assertEquals(listOf("opencell:1:nope", "something else", "opencell:1:nope"), seen)
    }
}
